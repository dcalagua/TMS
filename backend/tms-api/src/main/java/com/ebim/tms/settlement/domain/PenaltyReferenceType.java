package com.ebim.tms.settlement.domain;

/** What a penalty line deducts for (V66, ADR-024 part 2, item 6). Optional on the line. */
public enum PenaltyReferenceType {
    TRIP_EXCEPTION,
    DELIVERY_FAILED,
    LATE_ARRIVAL,
    OTHER
}
