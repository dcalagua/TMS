package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * How far apart two points are by road, and how long the drive takes (migration V38).
 *
 * <p>The one answer every consumer of {@link RoutingPort} gets: planning scoring kilometres,
 * rating multiplying a per-km component, sequencing ordering stops, and ETA projecting an arrival.
 * They all read the same record so that a shipment's distance means the same thing in the plan, on
 * the invoice and on the map.
 *
 * <p><b>Every answer says where it came from, in two independent parts.</b> {@link #source} says
 * how the number was <em>produced</em> - measured by a provider, or estimated from the coordinates -
 * and {@link #servedFromCache} says whether this particular read touched a provider or came out of
 * {@code tms.travel_estimate}. They are separate on purpose: an estimate served from cache is still
 * an estimate, and a single field carrying both would let storing a figure quietly promote it to a
 * measurement. A distance that cannot be traced to how it was produced is a number nobody can argue
 * with, and per-km money is computed from these.
 *
 * @param distanceKm     road distance in kilometres, never negative, {@code BigDecimal} because it
 *                       is multiplied by a rate
 * @param travelDuration driving time, excluding service time at the stop - that is the location's
 *                       own {@code service_time_minutes} and is added by whoever builds a schedule
 * @param provider       a stable name for what produced this, recorded so an estimate can be traced
 *                       back to the rules that made it after those rules have changed
 * @param source         how the figure was produced - see {@link RoutingSource}
 * @param servedFromCache whether this read came from {@code tms.travel_estimate} rather than from a
 *                        fresh computation. Independent of {@link #source}
 * @param calculatedAt   when the underlying figure was produced, not when it was read from cache
 */
public record TravelEstimate(
        BigDecimal distanceKm,
        Duration travelDuration,
        String provider,
        RoutingSource source,
        boolean servedFromCache,
        OffsetDateTime calculatedAt) {

    public TravelEstimate {
        if (distanceKm == null || distanceKm.signum() < 0) {
            throw new IllegalArgumentException("distance must be present and non-negative");
        }
        if (travelDuration == null || travelDuration.isNegative()) {
            throw new IllegalArgumentException("travel duration must be present and non-negative");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("an estimate must name what produced it");
        }
        if (source == null || calculatedAt == null) {
            throw new IllegalArgumentException("an estimate must carry its source and its timestamp");
        }
    }

    /**
     * A freshly computed estimate: the shape a provider or the local estimator returns.
     */
    public static TravelEstimate computed(BigDecimal distanceKm, Duration travelDuration, String provider,
            RoutingSource source, OffsetDateTime calculatedAt) {
        return new TravelEstimate(distanceKm, travelDuration, provider, source, false, calculatedAt);
    }

    /**
     * The same estimate, marked as having been read from the cache.
     *
     * <p>{@link #source} is deliberately untouched: how the number was made does not change because
     * somebody stored it.
     */
    public TravelEstimate fromCache() {
        return new TravelEstimate(distanceKm, travelDuration, provider, source, true, calculatedAt);
    }

    /** Driving minutes, rounded down - the unit schedules and ETAs are expressed in. */
    public long travelMinutes() {
        return travelDuration.toMinutes();
    }

    /** Whether this figure is a local estimate rather than something a router actually computed. */
    public boolean isEstimated() {
        return source == RoutingSource.FALLBACK;
    }
}
