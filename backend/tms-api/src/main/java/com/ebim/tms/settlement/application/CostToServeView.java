package com.ebim.tms.settlement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The cost-to-serve report (V68, ADR-026 section 7). Money is per currency, never summed across
 * currencies. <b>There is no margin</b>: TMS has no sales tariff, so it reports what serving cost and
 * nothing about what it earned ({@link #marginAvailable} is always false and says so to every client).
 *
 * @param basis the company's basis today; each run keeps the basis it was computed with
 */
public record CostToServeView(
        LocalDate from,
        LocalDate to,
        String groupBy,
        String basis,
        boolean marginAvailable,
        String marginNote,
        List<Totals> totals,
        List<Group> groups) {

    public static final String NO_MARGIN_NOTE =
            "No margin: TMS has no sales tariff, so it reports the cost to serve and not what was earned.";

    /**
     * @param key   the customer label, destination id or route id; null for "none"
     * @param label what a person reads; null for "none"
     * @param costPerKg     null when the rows that received cost weigh nothing known
     * @param costPerM3     null when they have no known volume
     * @param costPerOrder  null when no order received cost
     */
    public record Group(
            String key,
            String label,
            String currency,
            long trips,
            long orders,
            long unallocatedOrders,
            BigDecimal cost,
            BigDecimal weightKg,
            BigDecimal volumeM3,
            BigDecimal costPerKg,
            BigDecimal costPerM3,
            BigDecimal costPerOrder) {
    }

    /**
     * The range in one currency, by run.
     *
     * @param tripsNotAllocated runs with a cost and no measurable order: the cost stayed on the trip
     * @param unallocatedOrders orders that received nothing because their base was unknown
     */
    public record Totals(
            String currency,
            long trips,
            BigDecimal cost,
            BigDecimal allocated,
            long tripsNotAllocated,
            long unallocatedOrders,
            long invoicedTrips,
            long actualTrips,
            long estimatedTrips,
            BigDecimal costPerKg) {
    }
}
