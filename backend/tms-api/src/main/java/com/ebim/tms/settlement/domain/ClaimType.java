package com.ebim.tms.settlement.domain;

/**
 * What a freight claim is for. Mirrors {@code ck_freight_claim_type} (migration V71).
 *
 * <p>Six values, with {@link #OTHER} as the escape hatch. A claim is a commercial statement about
 * the goods or the service, not the operational problem that caused it - a breakdown is a
 * {@code TripExceptionType}; the late delivery it led to is a {@link #DELAY} claim.
 */
public enum ClaimType {
    /** The goods arrived, damaged. */
    DAMAGE,
    /** Fewer units arrived than were handed to the carrier. */
    SHORTAGE,
    /** Nothing arrived. */
    LOSS,
    /** It arrived, late enough to cost something. */
    DELAY,
    /** It went to the wrong place, or the wrong goods went. */
    WRONG_DELIVERY,
    /** Anything else; the description says what. */
    OTHER
}
