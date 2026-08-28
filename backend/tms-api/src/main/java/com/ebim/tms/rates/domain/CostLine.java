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
 * @param amount   never null, and never negative except for {@code MAXIMUM_ADJUSTMENT} - a ceiling
 *                 adjusts the total down, and rendering that as a positive number would read as one
 *                 more charge (V39, {@code ck_trip_cost_component_amount_sign}). {@code 0.00} for a
 *                 non-calculable line, so that
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

    /**
     * A measured line whose quantity source is stated by the caller.
     *
     * <p>Since V39 the distance's provenance is a fact about the estimate rather than about the
     * component - a shipment measured over its own stops and one falling back to a master
     * corridor are priced the same way and traced differently.
     */
    static CostLine measured(RateComponent component, BigDecimal rate, BigDecimal quantity, BigDecimal amount,
            CostQuantitySource source) {
        return new CostLine(component, CostComponentStatus.APPLIED, rate, quantity, component.unit(),
                source, amount, null);
    }

    static CostLine measured(RateComponent component, BigDecimal rate, BigDecimal quantity, BigDecimal amount) {
        return measured(component, rate, quantity, amount, component.quantitySource());
    }

    static CostLine notCalculable(RateComponent component) {
        return new CostLine(component, CostComponentStatus.NOT_CALCULABLE, null, null, null, null,
                TripCostCalculator.ZERO_MONEY, component.missingQuantityReason());
    }

    public boolean isApplied() {
        return status == CostComponentStatus.APPLIED;
    }
}
