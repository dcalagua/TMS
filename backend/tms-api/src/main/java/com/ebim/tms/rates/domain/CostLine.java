package com.ebim.tms.rates.domain;

import java.math.BigDecimal;

/**
 * One line of a calculated estimate, before it is persisted as a
 * {@code tms.trip_cost_component} row (migration V30).
 *
 * <p>Mirrors that table's shape exactly, including the constraint that ties the fields together:
 * an {@link CostComponentStatus#APPLIED} line carries no reason, a
 * {@link CostComponentStatus#NOT_CALCULABLE} one carries a reason, no rate, no quantity and an
 * amount of zero.
 *
 * @param rate     what the card charges per unit, or null for a flat component
 * @param quantity what the shipment supplied, or null for a flat component
 * @param amount   never null and never negative; {@code 0.00} for a non-calculable line, so that
 *                 every sum over these lines is a plain sum
 */
public record CostLine(
        RateComponent component,
        CostComponentStatus status,
        BigDecimal rate,
        BigDecimal quantity,
        CostUnit unit,
        CostQuantitySource quantitySource,
        BigDecimal amount,
        CostComponentReason reason) {

    static CostLine flat(RateComponent component, BigDecimal amount) {
        return new CostLine(component, CostComponentStatus.APPLIED, null, null, null, null, amount, null);
    }

    static CostLine measured(RateComponent component, BigDecimal rate, BigDecimal quantity, BigDecimal amount) {
        return new CostLine(component, CostComponentStatus.APPLIED, rate, quantity, component.unit(),
                component.quantitySource(), amount, null);
    }

    static CostLine notCalculable(RateComponent component) {
        return new CostLine(component, CostComponentStatus.NOT_CALCULABLE, null, null, null, null,
                TripCostCalculator.ZERO_MONEY, component.missingQuantityReason());
    }

    public boolean isApplied() {
        return status == CostComponentStatus.APPLIED;
    }
}
