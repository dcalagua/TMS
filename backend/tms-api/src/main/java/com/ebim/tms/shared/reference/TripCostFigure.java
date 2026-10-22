package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one shipment cost, as the carrier scorecard needs it: the estimate and the recorded actual,
 * in the cost row's currency. Produced by {@link TripCostAnalyticsPort#figuresForRange} and read by
 * {@code CarrierScorecardService}, which never sends one of these to a client - it only sums them
 * per carrier and currency.
 *
 * @param tripId          the shipment
 * @param currency        ISO 4217; TMS converts nothing
 * @param estimatedAmount the tariff's estimate, or null
 * @param actualAmount    the recorded actual, or null
 */
public record TripCostFigure(UUID tripId, String currency, BigDecimal estimatedAmount, BigDecimal actualAmount) {

    /**
     * The best figure known for the shipment: the actual once it has been recorded, the estimate
     * until then, null when there is neither.
     */
    public BigDecimal knownAmount() {
        return actualAmount != null ? actualAmount : estimatedAmount;
    }
}
