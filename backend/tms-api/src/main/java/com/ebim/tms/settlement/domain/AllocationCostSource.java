package com.ebim.tms.settlement.domain;

/**
 * Which figure an allocation run split (V68, ADR-026 section 3), in order of preference.
 */
public enum AllocationCostSource {

    /** What the carrier's approved documents billed for the trip, net of VAT (ADR-024, ADR-027). */
    INVOICED,

    /** The closed actual cost (V30). */
    ACTUAL,

    /** The rate card's estimate (V30). */
    ESTIMATED
}
