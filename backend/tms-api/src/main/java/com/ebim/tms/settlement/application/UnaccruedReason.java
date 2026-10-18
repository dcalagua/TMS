package com.ebim.tms.settlement.application;

/**
 * Why an executed trip has no accrual (V65, ADR-023). Listed, never papered over with an invented
 * figure.
 */
public enum UnaccruedReason {
    /** No carrier on the trip and no vehicle either: nobody to owe. */
    NO_CARRIER,
    /** The trip was never costed: no {@code trip_cost} row. */
    NO_TRIP_COST,
    /** Costed, but neither a closed actual nor an estimate gives a positive figure. */
    NOT_CALCULABLE,
    /** Priceable: the next sweep (or "accrue now") will create it. */
    PENDING_SWEEP
}
