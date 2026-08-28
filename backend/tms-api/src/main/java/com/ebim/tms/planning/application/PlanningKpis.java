package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What a proposal costs a day, in the terms a planner judges one by (JOB 05).
 *
 * <p><b>These exist so that two proposals can be compared.</b> Before them the only way to tell
 * {@code HEURISTIC_V1} from {@code PLANNING_V2} was to read both boards, which is not a comparison,
 * it is an opinion. Every figure here is computed from the proposal itself and from the travel
 * matrix that produced it, so the same input scores the same way twice.
 *
 * @param trips              shipments the proposal creates
 * @param vehicles           distinct vehicles it uses. Never more than {@link #trips}, and fewer
 *                           when a vehicle runs twice - which is the number a fleet manager reads
 * @param plannedOrders      orders placed on a trip
 * @param unplannedOrders    orders the engine could not place, each with a reason on the proposal
 * @param lateOrders         orders whose requested window closes before the projected arrival.
 *                           Soft: a late delivery is still a delivery, and refusing to plan one
 *                           would leave a customer with nothing rather than with something late
 * @param totalDistanceKm    the sum of every trip's legs. Zero when no location has coordinates,
 *                           which is a legitimate state and not an error
 * @param totalDurationMinutes driving plus service time across every trip
 * @param weightUtilizationPercent  load against capacity, averaged over the trips that carry a
 *                           limit. Null when no vehicle declared one - reporting 0% for "unknown"
 *                           would read as an empty truck
 * @param volumeUtilizationPercent  as above
 * @param palletUtilizationPercent  as above
 * @param distanceEstimated  true when any leg came from the local estimator rather than a router.
 *                           Carried onto the KPI block so a comparison of two engines cannot be
 *                           read as more precise than the distances underneath it
 * @param totalCost          <b>always null today.</b> Pricing a hypothetical trip needs a rating
 *                           port that takes a proposal rather than a persisted shipment, which is
 *                           JOB 06's. Left explicitly absent rather than filled with a plausible
 *                           number: a fabricated cost is worse than a missing one, because somebody
 *                           would compare two engines on it
 */
public record PlanningKpis(
        int trips,
        int vehicles,
        int plannedOrders,
        int unplannedOrders,
        int lateOrders,
        BigDecimal totalDistanceKm,
        long totalDurationMinutes,
        BigDecimal weightUtilizationPercent,
        BigDecimal volumeUtilizationPercent,
        BigDecimal palletUtilizationPercent,
        boolean distanceEstimated,
        BigDecimal totalCost) {

    public static final PlanningKpis NONE = new PlanningKpis(
            0, 0, 0, 0, 0, BigDecimal.ZERO, 0, null, null, null, false, null);

    /**
     * The share of the day's demand that got onto a truck. The one figure a planner starts from,
     * and the reason {@code unplannedOrders} is a first-class output rather than a residue.
     */
    public BigDecimal plannedRatePercent() {
        int total = plannedOrders + unplannedOrders;
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(plannedOrders * 100L)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /** Kilometres per order placed - the figure that says whether a plan is efficient or merely full. */
    public BigDecimal kilometresPerPlannedOrder() {
        if (plannedOrders == 0 || totalDistanceKm.signum() == 0) {
            return null;
        }
        return totalDistanceKm.divide(BigDecimal.valueOf(plannedOrders), 2, RoundingMode.HALF_UP);
    }

    /**
     * The average of the percentages that are known, or null when none is.
     *
     * <p>Averaged over trips rather than computed from summed load and summed capacity: two trips,
     * one full and one empty, is a fleet being used badly, and a pooled figure would report it as
     * half full and hide that.
     */
    static BigDecimal averagePercent(List<BigDecimal> percentages) {
        List<BigDecimal> known = percentages.stream().filter(java.util.Objects::nonNull).toList();
        if (known.isEmpty()) {
            return null;
        }
        BigDecimal sum = known.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(known.size()), 1, RoundingMode.HALF_UP);
    }
}
