package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookDelivery;
import com.ebim.tms.integration.domain.WebhookDeliveryAttempt;
import com.ebim.tms.integration.domain.WebhookAttemptOutcome;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One delivery with everything about it: the exact bytes that were sent, and every attempt that was
 * made, in order.
 *
 * <p>This is the screen a "you never sent us that shipment" conversation is settled from, which is
 * why it carries the payload the list view omits - the question at that point is precisely what left
 * TMS.
 *
 * @param payload the body as every attempt sent it, byte for byte
 */
public record WebhookDeliveryDetailView(
        WebhookDeliveryView delivery,
        String payload,
        List<Attempt> attempts) {

    /**
     * @param statusCode null when the call never produced a response at all - a timeout, a refused
     *     connection, a name that would not resolve. That difference is the first thing an
     *     integrator needs and the reason this is nullable rather than zero
     */
    public record Attempt(
            UUID id,
            int attemptNumber,
            OffsetDateTime attemptedAt,
            int durationMs,
            Integer statusCode,
            WebhookAttemptOutcome outcome,
            String error) {

        static Attempt from(WebhookDeliveryAttempt attempt) {
            return new Attempt(
                    attempt.id(),
                    attempt.attemptNumber(),
                    attempt.attemptedAt(),
                    attempt.durationMs(),
                    attempt.statusCode(),
                    attempt.outcome(),
                    attempt.error());
        }
    }

    public static WebhookDeliveryDetailView of(WebhookDelivery delivery, List<WebhookDeliveryAttempt> attempts) {
        return new WebhookDeliveryDetailView(
                WebhookDeliveryView.from(delivery),
                delivery.payload(),
                attempts.stream().map(Attempt::from).toList());
    }
}
