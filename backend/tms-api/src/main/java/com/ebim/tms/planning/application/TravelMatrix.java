package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.TravelEstimate;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Distances and drive times between the places one planning run touches, resolved before the engine
 * runs (migration V38, JOB 05).
 *
 * <p><b>Why the engine is handed a matrix instead of a port.</b> {@link PlanningEngine} is a pure
 * function - no repository, no clock, no network - and that is what makes a proposal reproducible
 * and provable on a machine with no database. Letting it call {@code RoutingPort} would give it a
 * cache read per leg and take that property away. So {@code AutoPlanningService}, which does have
 * the ports, resolves every leg once and hands the answers in.
 *
 * <p><b>Keyed on location ids, not coordinates.</b> The engine works in ids throughout; converting
 * to points is the resolving service's job, and it also means a location whose coordinates are
 * missing is simply absent here rather than represented by a null point the engine has to check.
 *
 * <p><b>A miss is not an error.</b> {@link #isKnown} lets an engine ask, and every distance-aware
 * decision degrades to the behaviour it had before V38 when the answer is unknown - which is what
 * keeps a company with half its locations un-geocoded able to plan at all.
 */
public record TravelMatrix(Map<Leg, TravelEstimate> legs) {

    /** No distances known at all - what an engine gets before any location has coordinates. */
    public static final TravelMatrix EMPTY = new TravelMatrix(Map.of());

    public TravelMatrix {
        legs = Map.copyOf(legs);
    }

    /** An ordered pair of places. Ordered because a road is not assumed symmetric - see V38. */
    public record Leg(UUID fromLocationId, UUID toLocationId) {
    }

    public boolean isKnown(UUID from, UUID to) {
        return from != null && to != null && (from.equals(to) || legs.containsKey(new Leg(from, to)));
    }

    /**
     * Whether this matrix actually has the leg, as against returning zero for it.
     *
     * <p>{@link #distanceKm} answers zero for an unknown leg, which is right for planning - an
     * engine must place orders on a day where half the destinations are ungeocoded - and
     * <b>wrong for pricing</b>. A per-kilometre charge over a summed-up pile of zeros is a price
     * that looks calculated and is not, which is the "do not invent the distance" rule V30 states.
     * Callers that will multiply a distance by money ask this first.
     */
    public boolean knows(UUID from, UUID to) {
        return from != null && from.equals(to) || legs.containsKey(new Leg(from, to));
    }

    /** Kilometres, or zero when the leg is unknown or is a place to itself. */
    public BigDecimal distanceKm(UUID from, UUID to) {
        if (from != null && from.equals(to)) {
            return BigDecimal.ZERO;
        }
        TravelEstimate estimate = legs.get(new Leg(from, to));
        return estimate == null ? BigDecimal.ZERO : estimate.distanceKm();
    }

    /** Driving time, or zero when the leg is unknown or is a place to itself. */
    public Duration travelTime(UUID from, UUID to) {
        if (from != null && from.equals(to)) {
            return Duration.ZERO;
        }
        TravelEstimate estimate = legs.get(new Leg(from, to));
        return estimate == null ? Duration.ZERO : estimate.travelDuration();
    }

    /** Driving minutes, the unit a shift is measured in. */
    public long travelMinutes(UUID from, UUID to) {
        return travelTime(from, to).toMinutes();
    }

    /** Whether any leg here was estimated rather than measured - surfaced onto the proposal's KPIs. */
    public boolean anyEstimated() {
        return legs.values().stream().anyMatch(TravelEstimate::isEstimated);
    }

    /** A builder for the resolving service, which adds legs one at a time as it walks the pairs. */
    public static final class Builder {

        private final Map<Leg, TravelEstimate> legs = new HashMap<>();

        public Builder add(UUID from, UUID to, TravelEstimate estimate) {
            if (from != null && to != null && !from.equals(to) && estimate != null) {
                legs.put(new Leg(from, to), estimate);
            }
            return this;
        }

        public TravelMatrix build() {
            return legs.isEmpty() ? EMPTY : new TravelMatrix(legs);
        }
    }
}
