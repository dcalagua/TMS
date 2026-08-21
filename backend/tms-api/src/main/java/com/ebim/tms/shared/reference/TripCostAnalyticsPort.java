package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * How the KPI report asks what a range of operating days cost, without
 * {@code com.ebim.tms.planning} depending on {@code com.ebim.tms.rates} (migration V33).
 * Implemented by {@code rates.infrastructure.TripCostAnalyticsAdapter}.
 *
 * <p>The third port between these two modules and the second pointing this way, after
 * {@link TripCostEstimationPort}. It exists rather than the report reading {@code tms.trip_cost}
 * itself for the reason the whole {@code shared.reference} package exists, and rather than the
 * report passing a set of trip ids for a plainer reason: a quarter of one company's shipments at
 * the stated scale is tens of thousands of UUIDs, which is not a parameter list, it is a table.
 *
 * <p><b>It aggregates and returns nothing per shipment.</b> No trip id, no rate card, no invoice
 * reference - only sums by currency. A report that could name what one shipment cost would be a
 * second, unaudited door onto {@code GET /rates/trips/{id}/cost}, reachable with a different
 * permission.
 */
public interface TripCostAnalyticsPort {

    /**
     * What the shipments planned between {@code from} and {@code to} (both inclusive) cost, one
     * entry per currency, ordered by currency so two calls with the same data return the same list.
     *
     * <p>Empty when nothing in the range has a cost row at all, which is the ordinary state of an
     * installation that has not entered its tariffs: costing is best-effort at confirmation
     * ({@link TripCostEstimationPort}), so a company can run for months with no cost recorded and
     * the report has to say "nothing to compare" rather than "zero".
     *
     * <p>Counts cancelled shipments too, deliberately. A cancelled trip that was already invoiced
     * cost real money - {@code TripCostingLookupPort} makes the same point - and a cost report that
     * dropped it would understate the month by exactly the amounts nobody wants to be reminded of.
     */
    List<TripCostTotals> totalsByCurrency(UUID companyId, LocalDate from, LocalDate to);
}
