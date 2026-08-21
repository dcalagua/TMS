package com.ebim.tms.integration.domain;

/**
 * Where one delivery stands. Mirrors {@code ck_webhook_delivery_status} (migration V35).
 *
 * <p>Three states and not four: there is deliberately no {@code SENDING}. A row being worked on is
 * held under {@code SELECT ... FOR UPDATE SKIP LOCKED} for the length of one attempt, so the lock
 * is the "in flight" marker - and unlike a status column it is released by the database when the
 * process holding it dies, which is exactly the case a {@code SENDING} row would get stuck in
 * forever.
 */
public enum WebhookDeliveryStatus {

    /** Owed, and due at {@code next_attempt_at}. The only state the dispatcher picks up. */
    PENDING,

    /** The receiver answered 2xx. Terminal, and the only outcome that is good news. */
    PROCESSED,

    /**
     * Given up on: the retry schedule ran out, or the receiver said something no retry can fix (a
     * 4xx that is not a timeout or a rate limit, or a 410 Gone).
     *
     * <p>Terminal for the dispatcher, not for an operator - {@code POST
     * /api/v1/webhooks/deliveries/{id}/retry} puts the row back in the queue once the receiving
     * side has been fixed.
     */
    FAILED
}
