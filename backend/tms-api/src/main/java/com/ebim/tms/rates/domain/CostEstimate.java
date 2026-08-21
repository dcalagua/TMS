package com.ebim.tms.rates.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@link TripCostCalculator} produced: one amount, the currency it is in, and every line that
 * explains it - including the lines that could not be calculated.
 *
 * @param lines in {@link RateComponent} declaration order, which is the order they were calculated
 *              in and the order a screen shows them in
 */
public record CostEstimate(String currency, BigDecimal amount, List<CostLine> lines) {

    public CostEstimate {
        lines = List.copyOf(lines);
    }

    /**
     * Whether every component the card charges for could actually be calculated.
     *
     * <p>An incomplete estimate is still a real estimate and is still stored - it is simply
     * understated by whatever the missing lines would have added, and both the API and the screen
     * say so. Suppressing it instead would leave a shipment looking as though nobody had priced
     * it, which is a worse answer than "priced, minus the line haul, because this trip has no
     * route".
     */
    public boolean isComplete() {
        return lines.stream().allMatch(CostLine::isApplied);
    }

    /** The lines that could not be calculated, for a message that can name them. */
    public List<CostLine> notCalculableLines() {
        return lines.stream().filter(line -> !line.isApplied()).toList();
    }
}
