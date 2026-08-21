package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookAttemptOutcome;

/**
 * What one call to a subscriber's endpoint came to (migration V35).
 *
 * <p>Either a response arrived, in which case {@link #statusCode} says what it was, or none did, in
 * which case {@link #transportError} says why. Never both, and never neither.
 *
 * @param transportError a short, sanitised description of a failure that produced no response -
 *     "connection timed out", "connection refused", "host not found". Never a stack trace and never
 *     a response body: the body of a webhook response is under the receiver's control and may
 *     contain anything at all, including a copy of the payload TMS just sent
 */
public record WebhookSendResult(Integer statusCode, String transportError, int durationMs) {

    public static WebhookSendResult responded(int statusCode, int durationMs) {
        return new WebhookSendResult(statusCode, null, durationMs);
    }

    public static WebhookSendResult failed(String transportError, int durationMs) {
        return new WebhookSendResult(null, transportError, durationMs);
    }

    /**
     * A call that never reached a server is always worth retrying: DNS, routing and TLS handshakes
     * fail transiently in a way an HTTP 400 does not.
     */
    public WebhookAttemptOutcome outcome() {
        return statusCode == null ? WebhookAttemptOutcome.RETRYABLE_FAILURE
                : WebhookAttemptOutcome.forStatus(statusCode);
    }

    /** What goes in {@code webhook_delivery.last_error}, or null when the delivery succeeded. */
    public String errorSummary() {
        if (transportError != null) {
            return transportError;
        }
        return outcome() == WebhookAttemptOutcome.DELIVERED ? null : "HTTP " + statusCode;
    }
}
