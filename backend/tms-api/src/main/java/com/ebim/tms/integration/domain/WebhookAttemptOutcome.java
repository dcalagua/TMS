package com.ebim.tms.integration.domain;

/**
 * What one HTTP call to a subscriber's endpoint came to. Mirrors
 * {@code ck_webhook_delivery_attempt_outcome} (migration V35).
 *
 * <p>The distinction that matters is the second one against the third. Retrying a timeout or a 503
 * is how a delivery survives someone else's deployment window; retrying a 400 is sending the same
 * rejected bytes to the same server on a schedule, which helps nobody and looks like an attack from
 * the other side.
 */
public enum WebhookAttemptOutcome {

    /** 2xx. */
    DELIVERED,

    /** A timeout, a connection failure, a 5xx, a 408 or a 429 - the receiver may yet recover. */
    RETRYABLE_FAILURE,

    /**
     * A 4xx that is neither of those, or a 410 Gone. The request itself is what the receiver
     * refused, so the same request will be refused again.
     */
    PERMANENT_FAILURE;

    public boolean isRetryable() {
        return this == RETRYABLE_FAILURE;
    }

    /**
     * What a response status means for the delivery.
     *
     * <p>The three exceptions to "a 4xx is the sender's fault and will not improve" are the ones
     * that are conventionally about timing rather than about the request:
     *
     * <ul>
     *   <li><b>408 Request Timeout</b> - the receiver ran out of patience, not out of agreement.</li>
     *   <li><b>429 Too Many Requests</b> - the receiver is explicitly asking for later, and
     *       answering that by giving up would punish exactly the endpoints that are being polite
     *       about load.</li>
     *   <li><b>425 Too Early</b> - the same thing said about replay protection.</li>
     * </ul>
     *
     * <p>410 Gone is grouped with the permanent failures on purpose even though it is a 4xx that
     * says something about the resource rather than the request: it is how a receiver says "stop
     * sending here", and continuing to retry after being told that is the behaviour that gets a
     * sender blocked.
     *
     * <p>A 3xx counts as a permanent failure. TMS does not follow redirects on a signed POST -
     * following one would mean re-sending this company's data, with its signature, to a location
     * nobody administratively approved, which is the SSRF hole {@code WebhookTargetPolicy} closes
     * being re-opened by the receiver instead of by the administrator.
     */
    public static WebhookAttemptOutcome forStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return DELIVERED;
        }
        if (statusCode >= 500) {
            return RETRYABLE_FAILURE;
        }
        return switch (statusCode) {
            case 408, 425, 429 -> RETRYABLE_FAILURE;
            default -> PERMANENT_FAILURE;
        };
    }
}
