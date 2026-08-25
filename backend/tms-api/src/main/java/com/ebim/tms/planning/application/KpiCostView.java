package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.KpiRate;
import com.ebim.tms.shared.reference.TripCostTotals;
import java.math.BigDecimal;

/**
 * What the range cost against what the tariffs said it would, in one currency
 * ({@code docs/domain/KPIS_REPORTING_V1.md}, section "Cost").
 *
 * <p><b>One of these per currency and no grand total.</b> TMS holds no rates of exchange and
 * refuses to invent one, so a company paying two carriers in two currencies gets two rows and adds
 * them up nowhere - see {@code TripCostTotals}.
 *
 * <p><b>Null, not zero, when the caller may not be told.</b> The whole list is absent from the
 * report for a caller without {@code rates.trip_cost:read} - see {@code KpiService}.
 *
 * @param currency            ISO 4217, as recorded
 * @param tripsEstimated      shipments in the range carrying an estimate
 * @param estimatedAmount     the sum of those estimates
 * @param tripsWithActual     shipments carrying a recorded actual
 * @param actualAmount        the sum of those actuals
 * @param tripsComparable     shipments carrying <em>both</em>. The only population
 *                            {@link #variance} is about, and reported beside it so a variance over
 *                            three invoiced shipments cannot be read as a variance over four
 *                            hundred estimated ones
 * @param comparableEstimated the estimated half of the comparable set
 * @param comparableActual    the actual half of the comparable set
 * @param variance            {@code comparableActual - comparableEstimated}, or null when nothing
 *                            is comparable. Positive means it cost more than the tariff said
 * @param variancePercent     the variance as a percentage of {@code comparableEstimated}, or null
 *                            when there is nothing to compare or the estimate was zero. Sent
 *                            because "1,200 over" means nothing without knowing whether the base
 *                            was 4,000 or 400,000
 */
public record KpiCostView(
        String currency,
        long tripsEstimated,
        BigDecimal estimatedAmount,
        long tripsWithActual,
        BigDecimal actualAmount,
        long tripsComparable,
        BigDecimal comparableEstimated,
        BigDecimal comparableActual,
        BigDecimal variance,
        BigDecimal variancePercent) {

    /**
     * Translates the port's totals into the report's row, deriving the two figures that are
     * arithmetic over what it already carries and nothing else.
     *
     * <p>The derivation lives here rather than on {@code TripCostTotals} for the reason every port
     * type in {@code shared.reference} is thin: that record is a contract between two modules, and
     * the shape of a percentage on a screen is not part of it.
     */
    public static KpiCostView from(TripCostTotals totals) {
        BigDecimal variance = totals.variance();
        return new KpiCostView(totals.currency(), totals.tripsEstimated(), totals.estimatedAmount(),
                totals.tripsWithActual(), totals.actualAmount(), totals.tripsComparable(),
                totals.comparableEstimated(), totals.comparableActual(), variance,
                variance == null ? null : KpiRate.percentOf(variance, totals.comparableEstimated()));
    }
}
