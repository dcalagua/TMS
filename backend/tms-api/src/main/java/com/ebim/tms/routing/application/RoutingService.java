package com.ebim.tms.routing.application;

import com.ebim.tms.routing.domain.RoutingProviderAdapter;
import com.ebim.tms.routing.domain.TravelEstimateRow;
import com.ebim.tms.routing.infrastructure.TravelEstimateRepository;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.TravelEstimate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The routing chain: cache, then provider, then a local estimate (migration V38).
 *
 * <pre>
 *   ask ──▶ same point? ──▶ 0 km, 0 min, no cache, no provider
 *       ──▶ cache, still fresh? ──▶ serve it
 *       ──▶ provider available? ──▶ ask it, store, serve it
 *       ──▶ local estimate ──▶ store, serve it, marked FALLBACK
 * </pre>
 *
 * <p><b>Never throws for a road it cannot measure.</b> A provider that times out, refuses or blows
 * up is counted and stepped over; a location with no coordinates comes back empty. Routing informs
 * decisions and does not get to stop a planner from making one - ADR-007's rule for positions,
 * applied to distances.
 *
 * <p><b>Every answer is cached, including the fallback.</b> Caching only the good answers would
 * make a night of planning recompute the same straight lines thousands of times, and the row
 * carries its own {@code source} so a later reader can still tell an estimate from a measurement.
 * When a real provider is configured, the estimates expire and are replaced by real ones without
 * anything having to purge them.
 *
 * <p><b>Two instances racing to cache the same leg is not an error.</b> Both computed the same
 * number; the loser's insert hits {@code uq_travel_estimate_leg} and is answered from the winner's
 * row. That is the same treatment {@code TripAssignmentService} gives its own unique-index race,
 * except that here the outcome is not even a conflict for the caller to see.
 */
@Service
public class RoutingService implements RoutingPort {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private static final String LOOKUP_METRIC = "tms.routing.lookups";
    private static final String PROVIDER_METRIC = "tms.routing.provider.calls";
    private static final String PROVIDER_TIMER = "tms.routing.provider.duration";
    private static final String MATRIX_TIMER = "tms.routing.matrix.duration";

    private final TravelEstimateRepository cache;
    private final List<RoutingProviderAdapter> adapters;
    private final LocalGeodesicRoutingProvider fallback;
    private final RoutingProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public RoutingService(TravelEstimateRepository cache, List<RoutingProviderAdapter> adapters,
            LocalGeodesicRoutingProvider fallback, RoutingProperties properties, MeterRegistry meterRegistry,
            Clock clock) {
        this.cache = cache;
        // Everything except the fallback, in declaration order. The fallback is held separately and
        // consulted last: it always answers, so leaving it in the list would make every adapter
        // after it unreachable, and its position would depend on bean ordering rather than on the
        // rule that it is the answer of last resort.
        this.adapters = adapters.stream().filter(adapter -> adapter != fallback).toList();
        this.fallback = fallback;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<TravelEstimate> estimate(UUID companyId, GeoPoint origin, GeoPoint destination) {
        if (origin == null || destination == null) {
            // Master data being incomplete, not a failure. Counted so that "why is planning
            // ignoring distance tonight" has an answer that is not a guess.
            count(LOOKUP_METRIC, "unknown");
            return Optional.empty();
        }
        if (origin.sameAs(destination)) {
            // Not cached and not asked about: a point is zero from itself in every provider, and a
            // row per stop-against-itself would be the largest and least useful part of the table.
            count(LOOKUP_METRIC, "same-point");
            return Optional.of(TravelEstimate.computed(BigDecimal.ZERO.setScale(3), Duration.ZERO,
                    fallback.name(), RoutingSource.FALLBACK, OffsetDateTime.now(clock)));
        }
        return Optional.of(resolve(companyId, origin, destination));
    }

    @Override
    @Transactional
    public Map<Leg, TravelEstimate> matrix(UUID companyId, List<GeoPoint> origins, List<GeoPoint> destinations) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Set<Leg> wanted = distinctLegs(origins, destinations);
            if (wanted.size() > properties.matrixLimit()) {
                throw new IllegalArgumentException("a routing matrix of " + wanted.size()
                        + " legs exceeds the configured limit of " + properties.matrixLimit());
            }

            Map<Leg, TravelEstimate> answers = new LinkedHashMap<>();
            for (Leg leg : wanted) {
                estimate(companyId, leg.origin(), leg.destination())
                        .ifPresent(estimate -> answers.put(leg, estimate));
            }
            return answers;
        } finally {
            sample.stop(Timer.builder(MATRIX_TIMER)
                    .description("Time to answer one routing matrix, cache reads and provider calls included")
                    .register(meterRegistry));
        }
    }

    /**
     * The distinct ordered pairs a matrix actually needs.
     *
     * <p>Collapses duplicates - an N x N over a stop list that visits the same destination twice
     * asks about that road once - and drops the diagonal, which {@link #estimate} answers without
     * touching anything. A {@link LinkedHashSet} so the order is the caller's, which makes a failure
     * reproducible.
     */
    private static Set<Leg> distinctLegs(List<GeoPoint> origins, List<GeoPoint> destinations) {
        Set<Leg> legs = new LinkedHashSet<>();
        for (GeoPoint origin : origins) {
            if (origin == null) {
                continue;
            }
            for (GeoPoint destination : destinations) {
                if (destination != null && !origin.sameAs(destination)) {
                    legs.add(new Leg(origin, destination));
                }
            }
        }
        return legs;
    }

    /** Cache, then provider, then local estimate. */
    private TravelEstimate resolve(UUID companyId, GeoPoint origin, GeoPoint destination) {
        RoutingProviderAdapter provider = firstAvailableAdapter();
        String providerName = provider == null ? fallback.name() : provider.name();

        Optional<TravelEstimateRow> cached =
                cache.findLeg(companyId, providerName, origin.latitude(), origin.longitude(),
                        destination.latitude(), destination.longitude());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (cached.isPresent() && cached.get().isFreshAt(now)) {
            count(LOOKUP_METRIC, "hit");
            return cached.get().toEstimate();
        }
        count(LOOKUP_METRIC, cached.isPresent() ? "expired" : "miss");

        TravelEstimate computed = compute(provider, origin, destination);
        store(companyId, origin, destination, computed, cached.orElse(null), now);
        return computed;
    }

    /**
     * Asks the provider, and falls back to the local estimate for every way that can go wrong.
     *
     * <p>The {@code catch} is broad on purpose. An adapter is expected to return empty rather than
     * throw, but an adapter is also the part of this most likely to be written by somebody else
     * against a vendor SDK, and a routing library's timeout arriving as an unchecked exception must
     * degrade this call to an estimate rather than fail a planning run. Counted and logged at warn,
     * never silently.
     */
    private TravelEstimate compute(RoutingProviderAdapter provider, GeoPoint origin, GeoPoint destination) {
        if (provider == null) {
            return localEstimate(origin, destination);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<TravelEstimate> answer = provider.estimate(origin, destination);
            if (answer.isPresent()) {
                count(PROVIDER_METRIC, "ok", provider.name());
                return answer.get();
            }
            count(PROVIDER_METRIC, "empty", provider.name());
        } catch (RuntimeException failed) {
            count(PROVIDER_METRIC, "error", provider.name());
            log.warn("Routing provider {} failed; falling back to a local estimate: {}",
                    provider.name(), failed.toString());
        } finally {
            sample.stop(Timer.builder(PROVIDER_TIMER)
                    .tag("provider", provider.name())
                    .description("Time a routing provider took to answer one leg")
                    .register(meterRegistry));
        }
        return localEstimate(origin, destination);
    }

    private TravelEstimate localEstimate(GeoPoint origin, GeoPoint destination) {
        count(LOOKUP_METRIC, "fallback");
        return fallback.estimate(origin, destination).orElseThrow(() -> new IllegalStateException(
                "the local estimator must always answer; it needs nothing but the two points"));
    }

    /**
     * Writes the answer into the cache, refreshing an expired row rather than adding a second one.
     *
     * <p>A losing race is caught and ignored: the winner stored the same number, and the caller
     * already has its answer in hand. Turning that into an error would make a cache the one part of
     * the product that fails under load.
     */
    private void store(UUID companyId, GeoPoint origin, GeoPoint destination, TravelEstimate estimate,
            TravelEstimateRow expired, OffsetDateTime now) {
        OffsetDateTime expiresAt = now.plus(properties.cacheTtl());
        try {
            if (expired != null) {
                expired.refresh(estimate, expiresAt);
                cache.saveAndFlush(expired);
            } else {
                cache.saveAndFlush(new TravelEstimateRow(companyId, origin, destination, estimate, expiresAt));
            }
        } catch (DataIntegrityViolationException raced) {
            count(LOOKUP_METRIC, "raced");
            log.debug("Another instance cached the same leg first; keeping its row.");
        }
    }

    /** The first configured adapter that says it can answer, or {@code null} for none. */
    private RoutingProviderAdapter firstAvailableAdapter() {
        for (RoutingProviderAdapter adapter : adapters) {
            if (adapter.isAvailable()) {
                return adapter;
            }
        }
        return null;
    }

    /**
     * Deletes expired rows. Exposed for a scheduled sweep rather than run from here: what the
     * retention period is and who runs it are deployment questions, and a cache that trimmed itself
     * on the read path would charge one unlucky planner for everybody's housekeeping.
     */
    @Transactional
    public int evictExpired() {
        int removed = cache.deleteExpired(OffsetDateTime.now(clock));
        if (removed > 0) {
            log.info("Evicted {} expired routing estimates.", removed);
        }
        return removed;
    }

    private void count(String metric, String outcome) {
        Counter.builder(metric).tag("outcome", outcome).register(meterRegistry).increment();
    }

    private void count(String metric, String outcome, String provider) {
        Counter.builder(metric).tag("outcome", outcome).tag("provider", provider)
                .register(meterRegistry).increment();
    }

    /** The provider that would answer right now, for diagnostics. */
    public String activeProviderName() {
        RoutingProviderAdapter adapter = firstAvailableAdapter();
        return adapter == null ? fallback.name() : adapter.name();
    }
}
