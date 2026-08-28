package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * How far a shipment drives and how long that takes (migration V38).
 *
 * <p>The first consumer of {@code RoutingPort}, and deliberately a real one rather than a
 * demonstration: "how long is this run" is a question a dispatcher asks about every trip on the
 * board, and until now the product could not answer it at all.
 *
 * <p><b>What it measures.</b> The drive from the run's origin to the first stop, then stop to stop
 * in the planner's own sequence. It stops at the last stop: whether a vehicle returns to base is a
 * fleet policy this product does not model, and adding a return leg nobody asked for would inflate
 * every figure by roughly half.
 *
 * <p><b>Service time is not in here.</b> {@link #totalDuration} is driving only. Time spent at a
 * dock is the location's own {@code service_time_minutes}, and mixing the two would make a figure
 * that is neither a drive time nor a shift length.
 *
 * @param totalDistanceKm  the sum of the legs, or zero for a trip with no measurable leg
 * @param totalDuration    driving time only
 * @param legs             each leg in sequence, so a planner can see which one is the long one
 * @param provider         what produced the figures, from the first leg - see {@code TravelEstimate}
 * @param estimated        true when any leg came from the local estimator rather than a router.
 *                         Surfaced rather than hidden: a total built partly from straight lines is
 *                         still useful and must not be read as a measurement
 * @param unmeasurableLegs legs that could not be estimated at all, because a location has no
 *                         coordinates. Reported so a total that is quietly short is visibly short
 */
public record TripRouteMetrics(
        BigDecimal totalDistanceKm,
        Duration totalDuration,
        List<TripRouteLegView> legs,
        String provider,
        boolean estimated,
        int unmeasurableLegs) {

    public static final TripRouteMetrics NONE =
            new TripRouteMetrics(BigDecimal.ZERO, Duration.ZERO, List.of(), null, false, 0);

    /** Driving minutes, the unit a schedule is written in. */
    public long totalMinutes() {
        return totalDuration.toMinutes();
    }

    /** Whether every leg of the trip could be measured. A false here explains a short total. */
    public boolean isComplete() {
        return unmeasurableLegs == 0;
    }

    /**
     * One leg of the run.
     *
     * @param fromStopSequence the sequence of the stop being left, or {@code null} for the leg out
     *                         of the origin - which has no stop and is not stop zero
     */
    public record TripRouteLegView(
            Integer fromStopSequence,
            String fromLabel,
            int toStopSequence,
            String toLabel,
            BigDecimal distanceKm,
            long travelMinutes,
            boolean estimated) {
    }
}
