package com.ebim.tms.shared.reference;

import java.math.BigDecimal;

/**
 * What the shipments of one date range cost, in one currency (migration V33). Produced by
 * {@link TripCostAnalyticsPort} and read by the KPI report.
 *
 * <p><b>One of these per currency, never a single total.</b> TMS has no rates of exchange and
 * refuses to invent one ({@code TripCost.currency}), so a company that pays two carriers in two
 * currencies gets two rows and adds them up nowhere. A summed "total cost" across currencies would
 * be the most expensive kind of number: one that looks authoritative and means nothing.
 *
 * @param currency          the ISO 4217 code these figures are in
 * @param tripsEstimated    how many shipments in the range carry an estimate
 * @param estimatedAmount   the sum of those estimates; zero when {@code tripsEstimated} is zero
 * @param tripsWithActual   how many carry a recorded actual
 * @param actualAmount      the sum of those actuals
 * @param tripsComparable   how many carry <em>both</em>, and are therefore the only shipments
 *                          {@link #variance()} is computed over. Kept beside the two counts rather
 *                          than left to be inferred: a range where forty shipments were estimated
 *                          and three have been invoiced has a variance about three shipments, and a
 *                          screen that cannot say so would read it as a variance about forty
 * @param comparableEstimated the estimated half of the comparable set
 * @param comparableActual    the actual half of the comparable set
 */
public record TripCostTotals(
        String currency,
        long tripsEstimated,
        BigDecimal estimatedAmount,
        long tripsWithActual,
        BigDecimal actualAmount,
        long tripsComparable,
        BigDecimal comparableEstimated,
        BigDecimal comparableActual) {

    /**
     * {@code comparableActual - comparableEstimated}, or null when no shipment in the range carries
     * both figures. Positive means the range cost more than the tariffs said it would.
     *
     * <p>Computed over the comparable set and never over the two whole-range sums, which is the
     * only definition that is not misleading: subtracting the actuals of three shipments from the
     * estimates of forty produces a large negative number that reads as a saving and is nothing of
     * the kind. Same rule {@code TripCost.variance} applies to one shipment, applied to a set.
     */
    public BigDecimal variance() {
        return tripsComparable == 0 ? null : comparableActual.subtract(comparableEstimated);
    }
}
