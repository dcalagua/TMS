package com.ebim.tms.shared.settings;

/**
 * How a trip's cost is split between the orders it carried (migration V68, ADR-026, decision D-08).
 *
 * <p>Read by {@code settlement}, written by {@code iam} with the rest of the company settings. A
 * company without one follows {@code tms.finance.cost-allocation.default-basis} ({@link #WEIGHT}).
 */
public enum CostAllocationBasis {

    /** The kilograms of each assignment row. */
    WEIGHT,

    /** The planning volume of each row - the effective volume after ADR-022. */
    EFFECTIVE_VOLUME,

    /** The floor area of each row (ADR-022). */
    FLOOR_AREA,

    /** The pallets of each row. */
    PALLETS,

    /** Equal parts per stop, then by weight within the stop. */
    STOPS,

    /** Equal parts per assignment row. */
    EQUAL
}
