package com.ebim.tms.settlement.domain;

/**
 * How an accepted freight claim is recovered from the carrier (migration V71). Both are concepts
 * settlement already has (V66); TMS moves no money (D-01).
 *
 * <ul>
 *   <li>{@link #PENALTY} - deducted on the carrier's next invoice of the trip, as a PENALTY line
 *       with reference type FREIGHT_CLAIM. Proposed to whoever keys the invoice, applied only when
 *       they confirm it.
 *   <li>{@link #CREDIT_NOTE} - the carrier sends a credit note; TMS receives it, it does not issue
 *       one.
 * </ul>
 */
public enum RecoveryMethod {
    PENALTY,
    CREDIT_NOTE
}
