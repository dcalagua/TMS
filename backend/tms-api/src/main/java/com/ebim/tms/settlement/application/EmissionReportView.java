package com.ebim.tms.settlement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The carbon footprint of a period, by customer, destination or route (migration V73), over the trips
 * not cancelled whose planning date is in the range.
 *
 * <p>A trip with no figure contributes nothing and is <b>counted</b> by its reason, so a total that is
 * short says why: no factor on its vehicle type, no measurable route, no vehicle.
 *
 * @param truncated true when the period had more trips than one report reads; narrow the range
 */
public record EmissionReportView(
        LocalDate from,
        LocalDate to,
        String groupBy,
        String basis,
        Totals totals,
        List<Group> groups,
        boolean truncated) {

    /**
     * @param kgCo2e          the footprint of the trips with a figure
     * @param unallocatedKgCo2e what could not be split to any order (no order measurable on the basis)
     */
    public record Totals(
            long trips,
            long tripsCalculated,
            long tripsEstimated,
            long tripsWithoutFactor,
            long tripsWithoutDistance,
            long tripsWithoutVehicle,
            BigDecimal kgCo2e,
            BigDecimal distanceKm,
            BigDecimal unallocatedKgCo2e) {
    }

    /**
     * @param weightKg        the kilograms of the orders that received a share
     * @param kgCo2ePerTonne  footprint per tonne carried; null without weight
     */
    public record Group(
            String key,
            String label,
            long trips,
            long orders,
            BigDecimal kgCo2e,
            BigDecimal weightKg,
            BigDecimal kgCo2ePerTonne) {
    }
}
