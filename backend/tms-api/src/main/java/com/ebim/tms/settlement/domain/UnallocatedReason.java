package com.ebim.tms.settlement.domain;

/** Why some or all of a trip's orders received no cost (V68, ADR-026 section 6). */
public enum UnallocatedReason {

    /** Some orders had no measure of the basis; the cost went to the others. */
    NO_BASIS,

    /** No order had a measure of the basis: nothing was allocated. */
    NO_MEASURABLE_ORDER,

    /** The trip carried no order. */
    NO_ORDERS
}
