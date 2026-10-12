package com.ebim.tms.planning.domain;

/**
 * Where a logistics document on a trip stands, as the carrier reported it (ADR-018, V57).
 *
 * <p>No partial state, on purpose: a document is handed over or it is not. Partial is a property of
 * the goods, and the goods already have it ({@link DeliveryResult#PARTIAL}, ADR-009). Two places
 * recording "partial" would be two answers to one question.
 */
public enum DocumentDeliveryStatus {

    /** Nobody has reported anything yet. */
    PENDING,

    /** Handed to the customer. A photo is mandatory. */
    DELIVERED,

    /** The customer refused to take it. A reason is mandatory. */
    REJECTED,

    /** It could not be handed over at all. A reason is mandatory. */
    NOT_DELIVERED;

    /** Whether this outcome must say why (mirrors {@code ck_document_delivery_reason_by_status}). */
    public boolean requiresReason() {
        return this == REJECTED || this == NOT_DELIVERED;
    }
}
