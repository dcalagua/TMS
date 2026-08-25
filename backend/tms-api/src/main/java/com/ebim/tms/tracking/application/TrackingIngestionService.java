package com.ebim.tms.tracking.application;

import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TrackingIntakeOutcome;
import com.ebim.tms.shared.reference.TrackingIntakeResult;
import com.ebim.tms.shared.reference.TrackingReport;
import com.ebim.tms.shared.reference.TripTrackingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.tracking.domain.TrackingPosition;
import com.ebim.tms.tracking.infrastructure.TrackingPositionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code tms.tracking_position} (migration V29), and the one place the three
 * rules that decide the fate of a reported position live.
 *
 * <h2>The three rules</h2>
 *
 * <ol>
 *   <li><b>Is it usable?</b> Coordinates in range, a time that is neither in the future nor older
 *       than {@link TrackingProperties#maxAge()}, a provider slug of the shape the schema accepts.
 *       Anything else is {@code INVALID} - a refusal the sender can fix.</li>
 *   <li><b>Is there a shipment on the road to attach it to?</b> Resolved by number, inside the
 *       caller's company, through {@link TripTrackingLookupPort}. Not found is
 *       {@code UNKNOWN_SHIPMENT}; found but not out is {@code NOT_TRACKABLE}.</li>
 *   <li><b>Do we want to keep it?</b> The sampling rule. One point per
 *       {@link TrackingProperties#minInterval()} per (shipment, feed); denser points are accepted
 *       and dropped, older ones than we already hold are {@code STALE}, and an exact repeat is
 *       {@code DUPLICATE}.</li>
 * </ol>
 *
 * <p>The third rule is the one that keeps this table a fixed size per vehicle-day rather than a
 * function of how enthusiastic somebody's telematics box is, and it is enforced <em>here</em>
 * rather than by asking partners to send less. A sender that pushes every second is not doing
 * anything wrong; it is doing what its vendor's default does, and an API whose scalability depends
 * on every partner reconfiguring their equipment does not have a scalability story.
 *
 * <h2>Why a run and not a position</h2>
 *
 * <p>The sampling and staleness rules are statements about a sequence, so the unit of work is the
 * run: the newest stored instant per (trip, feed) is read once for the whole delivery, and each
 * kept point advances it in memory. Per position, that read would happen two hundred times to
 * produce at most a handful of distinct answers.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No status is derived, no stop is closed, no exception is opened and no timeline entry is
 * written. A position is not evidence that anything happened: a vehicle standing at a customer's
 * gate and a vehicle standing in traffic outside it produce the same point, and letting a
 * measurement move a shipment's lifecycle would move accountability for the timeline from the
 * person who reported it to a GPS box (migration V29, "Deliberately NOT here"). Nothing in TMS
 * reads this table except the screen that draws it.
 */
@Service
public class TrackingIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TrackingIngestionService.class);

    /** Mirrors {@code ck_tracking_position_provider_shape}; rejected before any work. */
    private static final Pattern PROVIDER = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}$");

    /** Mirrors {@code ck_tracking_position_external_vehicle_reference_length} and its sibling. */
    private static final int MAX_REFERENCE_LENGTH = 128;

    /** Mirrors {@code ck_tracking_position_speed_range} - see V29 on why 400 and not a limit. */
    private static final BigDecimal MAX_SPEED_KPH = new BigDecimal("400");

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final BigDecimal MAX_HEADING_DEGREES = new BigDecimal("360");

    /**
     * Counts every position this service decides on, tagged {@code outcome}. The one number an
     * operator watches to see a feed working: a provider whose positions are 100% {@code THINNED}
     * is spending bandwidth on nothing, and one that is 100% {@code UNKNOWN_SHIPMENT} is pointed at
     * the wrong tenant - neither is visible from an HTTP status, because both are 200.
     */
    private static final String POSITIONS_METRIC = "tms.tracking.positions";

    private final TrackingPositionRepository positionRepository;
    private final TripTrackingLookupPort tripLookup;
    private final TrackingProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public TrackingIngestionService(TrackingPositionRepository positionRepository,
            TripTrackingLookupPort tripLookup, TrackingProperties properties, MeterRegistry meterRegistry,
            Clock clock) {
        this.positionRepository = positionRepository;
        this.tripLookup = tripLookup;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        log.info("Tracking intake: one position per {}s per (shipment, feed), accepting reports up to {}h old "
                        + "and at most {}s ahead of this clock.",
                properties.minInterval().toSeconds(), properties.maxAge().toHours(),
                properties.maxFutureSkew().toSeconds());
    }

    /**
     * Decides and stores a run of reports, one result per report at the index it was sent.
     *
     * <p>Nothing here throws for a bad item - see {@code TrackingIntakePort}. The unique index
     * {@code uq_tracking_position_feed_instant} is the concurrency backstop behind the duplicate
     * rule: two identical deliveries in flight at once can both pass the check and one will lose
     * the insert, failing that delivery with a 500 that the inbox records and the sender's retry
     * resolves - every point of it is then a {@code DUPLICATE}. That is the same race, and the same
     * self-healing answer, {@code IntegrationInboxService.record} documents for its own table.
     */
    @Transactional
    public List<TrackingIntakeResult> record(CompanyScope scope, List<TrackingReport> reports) {
        if (reports.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        List<TrackingIntakeResult> results = new ArrayList<>();
        for (int index = 0; index < reports.size(); index++) {
            Candidate candidate = validate(index, reports.get(index));
            if (candidate.refusal() != null) {
                results.add(candidate.refusal());
            } else {
                candidates.add(candidate);
            }
        }

        Map<String, TrackedTrip> trips = resolveTrips(scope, candidates);
        List<Candidate> attachable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            TrackedTrip trip = trips.get(candidate.shipmentNumber());
            if (trip == null) {
                results.add(TrackingIntakeResult.refused(candidate.index(), TrackingIntakeOutcome.UNKNOWN_SHIPMENT,
                        "No shipment '" + candidate.shipmentNumber() + "' in this company."));
            } else if (!trip.trackable()) {
                results.add(TrackingIntakeResult.refused(candidate.index(), TrackingIntakeOutcome.NOT_TRACKABLE,
                        "Shipment '" + candidate.shipmentNumber() + "' is " + trip.status()
                                + " and is not out on the road."));
            } else {
                attachable.add(candidate.attachedTo(trip.id()));
            }
        }

        results.addAll(sampleAndStore(scope, attachable));
        results.sort((left, right) -> Integer.compare(left.index(), right.index()));
        results.forEach(result -> count(result.outcome()));
        return List.copyOf(results);
    }

    /**
     * Applies the sampling rule to everything that survived, feed by feed.
     *
     * <p>Sorted by time inside each (trip, feed) group before anything is decided: a run that
     * arrives out of order - which is normal for a device flushing a buffer - would otherwise have
     * its own later points thin its earlier ones and then declare them stale, and the same delivery
     * would produce different results depending on how the sender happened to order it.
     */
    private List<TrackingIntakeResult> sampleAndStore(CompanyScope scope, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<FeedKey, OffsetDateTime> keptSoFar = watermarks(scope, candidates);
        Map<FeedKey, List<Candidate>> byFeed = new HashMap<>();
        for (Candidate candidate : candidates) {
            byFeed.computeIfAbsent(new FeedKey(candidate.tripId(), candidate.provider()), key -> new ArrayList<>())
                    .add(candidate);
        }

        List<TrackingIntakeResult> results = new ArrayList<>();
        List<Candidate> kept = new ArrayList<>();
        for (Map.Entry<FeedKey, List<Candidate>> feed : byFeed.entrySet()) {
            List<Candidate> ordered = new ArrayList<>(feed.getValue());
            ordered.sort((left, right) -> left.occurredAt().compareTo(right.occurredAt()));

            OffsetDateTime lastKept = keptSoFar.get(feed.getKey());
            for (Candidate candidate : ordered) {
                TrackingIntakeOutcome outcome = decide(candidate.occurredAt(), lastKept);
                if (outcome == TrackingIntakeOutcome.RECORDED) {
                    lastKept = candidate.occurredAt();
                    kept.add(candidate);
                } else {
                    results.add(TrackingIntakeResult.skipped(candidate.index(), outcome));
                }
            }
        }

        // saveAll and not one save per point: a run of two hundred is one flush, which is the
        // difference between this endpoint costing a round trip per position and costing one for
        // the delivery. The returned list is in the order it was given, which is what lets each
        // generated id be handed back to the report that produced it.
        List<TrackingPosition> saved = positionRepository.saveAll(
                kept.stream().map(candidate -> candidate.toPosition(scope.companyId())).toList());
        for (int i = 0; i < kept.size(); i++) {
            results.add(TrackingIntakeResult.recorded(kept.get(i).index(), saved.get(i).id()));
        }
        return results;
    }

    /**
     * The sampling decision itself, and the whole of it.
     *
     * <p>Four lines, and every branch of the sampling policy is one of them. Kept as its own method
     * rather than inlined into the loop because it is the rule that decides how large this table
     * gets: it should be readable on its own, next to the enum whose values it returns.
     */
    private TrackingIntakeOutcome decide(OffsetDateTime occurredAt, OffsetDateTime lastKept) {
        if (lastKept == null) {
            return TrackingIntakeOutcome.RECORDED;
        }
        if (occurredAt.isEqual(lastKept)) {
            return TrackingIntakeOutcome.DUPLICATE;
        }
        if (occurredAt.isBefore(lastKept)) {
            return TrackingIntakeOutcome.STALE;
        }
        return occurredAt.isBefore(lastKept.plus(properties.minInterval()))
                ? TrackingIntakeOutcome.THINNED
                : TrackingIntakeOutcome.RECORDED;
    }

    /** The newest instant already stored per (trip, feed), one query per distinct feed. */
    private Map<FeedKey, OffsetDateTime> watermarks(CompanyScope scope, List<Candidate> candidates) {
        Map<String, List<UUID>> tripsByProvider = new HashMap<>();
        for (Candidate candidate : candidates) {
            tripsByProvider.computeIfAbsent(candidate.provider(), provider -> new ArrayList<>())
                    .add(candidate.tripId());
        }

        Map<FeedKey, OffsetDateTime> watermarks = new HashMap<>();
        tripsByProvider.forEach((provider, tripIds) -> positionRepository
                .findWatermarks(scope.companyId(), new LinkedHashSet<>(tripIds), provider)
                .forEach(row -> watermarks.put(new FeedKey(row.getTripId(), row.getProvider()), row.getLatest())));
        return watermarks;
    }

    private Map<String, TrackedTrip> resolveTrips(CompanyScope scope, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        candidates.forEach(candidate -> numbers.add(candidate.shipmentNumber()));
        return tripLookup.findByShipmentNumbers(scope.companyId(), List.copyOf(numbers));
    }

    /**
     * Everything a position must satisfy before TMS looks up anything, checked against the schema's
     * own constraints rather than against a looser opinion of them: a report that would violate a
     * CHECK is refused here with a sentence naming the field, instead of reaching the database and
     * failing a delivery of two hundred points with a message about a constraint.
     */
    private Candidate validate(int index, TrackingReport report) {
        String shipmentNumber = normalizeShipmentNumber(report.shipmentNumber());
        if (shipmentNumber == null) {
            return Candidate.refused(index, "shipmentNumber is required.");
        }
        String provider = report.provider() == null ? null : report.provider().trim().toLowerCase(Locale.ROOT);
        if (provider == null || !PROVIDER.matcher(provider).matches()) {
            return Candidate.refused(index, "provider must be 2 to 64 characters of lowercase letters, digits, "
                    + "'.', '_' or '-', starting with a letter or a digit.");
        }
        if (report.occurredAt() == null) {
            return Candidate.refused(index, "occurredAt is required.");
        }
        if (report.latitude() == null || report.longitude() == null) {
            return Candidate.refused(index, "latitude and longitude are both required.");
        }
        if (outside(report.latitude(), MIN_LATITUDE, MAX_LATITUDE)) {
            return Candidate.refused(index, "latitude must be between -90 and 90.");
        }
        if (outside(report.longitude(), MIN_LONGITUDE, MAX_LONGITUDE)) {
            return Candidate.refused(index, "longitude must be between -180 and 180.");
        }
        if (report.speedKph() != null && outside(report.speedKph(), BigDecimal.ZERO, MAX_SPEED_KPH)) {
            return Candidate.refused(index, "speedKph must be between 0 and 400 - a larger value is a unit "
                    + "mismatch rather than a faster vehicle.");
        }
        if (report.headingDegrees() != null
                && (report.headingDegrees().signum() < 0
                        || report.headingDegrees().compareTo(MAX_HEADING_DEGREES) >= 0)) {
            return Candidate.refused(index, "headingDegrees must be at least 0 and less than 360.");
        }
        if (tooLong(report.externalVehicleReference()) || tooLong(report.correlationReference())) {
            return Candidate.refused(index, "externalVehicleReference and correlationReference are at most "
                    + MAX_REFERENCE_LENGTH + " characters.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (report.occurredAt().isAfter(now.plus(properties.maxFutureSkew()))) {
            return Candidate.refused(index, "occurredAt is in the future. Check the reporting device's clock.");
        }
        if (report.occurredAt().isBefore(now.minus(properties.maxAge()))) {
            return Candidate.refused(index, "occurredAt is older than this deployment accepts ("
                    + properties.maxAge().toHours() + " hours).");
        }

        return new Candidate(index, shipmentNumber, provider, report, null, null);
    }

    private static String normalizeShipmentNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // Upper-cased because shipment numbers are generated upper-case ("SH-00000123", migration
        // V19) and a partner echoing one back in lower case has made no business error.
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }

    private static boolean tooLong(String value) {
        return value != null && value.trim().length() > MAX_REFERENCE_LENGTH;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void count(TrackingIntakeOutcome outcome) {
        Counter.builder(POSITIONS_METRIC).tag("outcome", outcome.name()).register(meterRegistry).increment();
    }

    /** One (shipment, feed) pair - the grain the sampling rule is applied at. */
    private record FeedKey(UUID tripId, String provider) {
    }

    /**
     * A report that has passed validation, on its way to a decision. Carries the index so a result
     * can always name the item it is about, and the resolved {@code tripId} once there is one.
     */
    private record Candidate(int index, String shipmentNumber, String provider, TrackingReport report, UUID tripId,
            TrackingIntakeResult refusal) {

        static Candidate refused(int index, String reason) {
            return new Candidate(index, null, null, null, null,
                    TrackingIntakeResult.refused(index, TrackingIntakeOutcome.INVALID, reason));
        }

        Candidate attachedTo(UUID resolvedTripId) {
            return new Candidate(index, shipmentNumber, provider, report, resolvedTripId, null);
        }

        OffsetDateTime occurredAt() {
            return report.occurredAt();
        }

        TrackingPosition toPosition(UUID companyId) {
            return new TrackingPosition(companyId, tripId, report.occurredAt(), report.latitude(),
                    report.longitude(), report.speedKph(), report.headingDegrees(), provider,
                    blankToNull(report.externalVehicleReference()), blankToNull(report.correlationReference()));
        }
    }
}
