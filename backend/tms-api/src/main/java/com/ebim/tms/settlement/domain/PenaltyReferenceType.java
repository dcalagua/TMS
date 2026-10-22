package com.ebim.tms.settlement.domain;

/** What a penalty line deducts for (V66, ADR-024 part 2, item 6). Optional on the line. */
public enum PenaltyReferenceType {
    TRIP_EXCEPTION,
    DELIVERY_FAILED,
    LATE_ARRIVAL,
    OTHER,
    /**
     * An accepted freight claim (V71): the reference id is the claim's, and the deduction is
     * checked against what was accepted when the invoice is received.
     */
    FREIGHT_CLAIM
}
