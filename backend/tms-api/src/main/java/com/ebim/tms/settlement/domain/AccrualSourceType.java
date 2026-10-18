package com.ebim.tms.settlement.domain;

/**
 * What caused a movement (V65). With {@code source_key} it is the idempotency key of every automatic
 * movement: {@code uq_freight_accrual_entry_source}.
 */
public enum AccrualSourceType {
    /** The trip left (trigger DISPATCHED). Key: the trip id. */
    TRIP_DISPATCHED,
    /** The trip completed (trigger DELIVERED). Key: the trip id. */
    TRIP_COMPLETED,
    /** The trip cost was closed with an actual. Key: the closing instant. */
    TRIP_COST_ACTUAL,
    /** An invoice billing the trip was approved. Key: the invoice id. */
    INVOICE_APPROVED,
    /** A person's act. Key: a fresh id; a reason is required. */
    MANUAL
}
