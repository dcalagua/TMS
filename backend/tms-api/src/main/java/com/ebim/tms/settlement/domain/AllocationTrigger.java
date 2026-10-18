package com.ebim.tms.settlement.domain;

/** What made an allocation run (V68, ADR-026 section 5). */
public enum AllocationTrigger {

    /** The trip left and had no allocation yet: the first run, on the figure known then. */
    TRIP_EXECUTED,

    /** The trip's cost was closed (or closed again) after the current run. */
    TRIP_COST_CLOSED,

    /** An approved carrier document billed the trip, in the same transaction as the approval. */
    INVOICE_APPROVED,

    /** A person asked ("Recalcular"). */
    MANUAL
}
