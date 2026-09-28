package com.ebim.tms.planning.domain;

/**
 * Whether a dispatch document agrees with the plan (ADR-013 section 2). Held by each document, not
 * by the trip: a trip may one day have several documents, and its verification is derived on read.
 */
public enum DispatchVerificationStatus {
    /** No document yet - only ever a derived value for a trip. */
    UNVERIFIED,
    MATCHED,
    MISMATCH,
    /** Matches, and the trip had been dispatched by a person past the warehouse's confirmation. */
    OVERRIDDEN
}
