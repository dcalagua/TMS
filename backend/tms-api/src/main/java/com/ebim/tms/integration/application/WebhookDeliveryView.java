package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookDelivery;
import com.ebim.tms.integration.domain.WebhookDeliveryStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One delivery as the administration API shows it: which event, to which endpoint, how it went and
 * when it will next be tried.
 *
 * <p>The payload is deliberately absent from the list. It is stored - a retry has to send the same
 * bytes - and it is available from the single-delivery endpoint, where somebody who is actually
 * debugging a receiver can read it. Putting it in a page of fifty rows would ship fifty bodies to a
 * screen that shows none of them.
 *
 * @param nextAttemptAt when the dispatcher will try again. Meaningful only while
 *     {@link #status} is {@code PENDING}; on a finished delivery it is the last value the scheduler
 *     wrote and says nothing
 */
public record WebhookDeliveryView(
        UUID id,
        UUID subscriptionId,
        String subscriptionName,
        UUID eventId,
        String eventType,
        OffsetDateTime occurredAt,
        WebhookDeliveryStatus status,
        int attemptCount,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime completedAt,
        Integer lastStatusCode,
        String lastError,
        OffsetDateTime createdAt) {

    public static WebhookDeliveryView from(WebhookDelivery delivery) {
        return new WebhookDeliveryView(
                delivery.id(),
                delivery.subscription().id(),
                delivery.subscription().name(),
                delivery.eventId(),
                delivery.eventType(),
                delivery.occurredAt(),
                delivery.status(),
                delivery.attemptCount(),
                delivery.nextAttemptAt(),
                delivery.lastAttemptAt(),
                delivery.completedAt(),
                delivery.lastStatusCode(),
                delivery.lastError(),
                delivery.createdAt());
    }
}
