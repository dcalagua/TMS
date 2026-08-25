package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a delivery stops being retried, and what it looks like afterwards.
 *
 * <p>All of it decided on the entity rather than in the dispatcher, so that there is one answer to
 * "is this delivery finished" instead of one per call site.
 */
class WebhookDeliveryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);

    private WebhookDelivery delivery() {
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(), "WMS", null,
                "https://partner.example/hooks", "ciphertext", "7fQ2", UUID.randomUUID());
        return new WebhookDelivery(UUID.randomUUID(), subscription, UUID.randomUUID(),
                WebhookEventType.SHIPMENT_CONFIRMED, NOW, "{\"id\":\"x\"}", NOW);
    }

    @Test
    @DisplayName("a new delivery is pending, unattempted and due now")
    void initialState() {
        WebhookDelivery delivery = delivery();

        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.attemptCount()).isZero();
        assertThat(delivery.nextAttemptNumber()).isEqualTo(1);
        assertThat(delivery.completedAt()).isNull();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a delivered attempt finishes the delivery")
    void delivered() {
        WebhookDelivery delivery = delivery();

        delivery.recordAttempt(WebhookAttemptOutcome.DELIVERED, 200, null, NOW, null);

        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
        assertThat(delivery.completedAt()).isEqualTo(NOW);
        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(delivery.lastStatusCode()).isEqualTo(200);
        assertThat(delivery.isPending()).isFalse();
    }

    @Test
    @DisplayName("a retryable failure with a schedule left keeps the delivery pending")
    void retryable() {
        WebhookDelivery delivery = delivery();

        delivery.recordAttempt(WebhookAttemptOutcome.RETRYABLE_FAILURE, 503, "HTTP 503", NOW, NOW.plusMinutes(1));

        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.completedAt()).isNull();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(delivery.lastError()).isEqualTo("HTTP 503");
        assertThat(delivery.nextAttemptNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("no next attempt means the delivery has failed, whatever the reason")
    void exhaustedOrPermanent() {
        WebhookDelivery exhausted = delivery();
        exhausted.recordAttempt(WebhookAttemptOutcome.RETRYABLE_FAILURE, 503, "HTTP 503", NOW, null);

        WebhookDelivery refused = delivery();
        refused.recordAttempt(WebhookAttemptOutcome.PERMANENT_FAILURE, 400, "HTTP 400", NOW, null);

        // The two end in the same state on purpose: what separates them is already recorded on the
        // attempt row and in lastError, and the queue only cares whether anything is still owed.
        assertThat(exhausted.status()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(exhausted.completedAt()).isEqualTo(NOW);
        assertThat(refused.status()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(refused.completedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a manual retry re-queues without resetting the attempt numbering")
    void requeue() {
        WebhookDelivery delivery = delivery();
        delivery.recordAttempt(WebhookAttemptOutcome.PERMANENT_FAILURE, 400, "HTTP 400", NOW, null);

        delivery.requeue(NOW.plusHours(2));

        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.completedAt()).isNull();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusHours(2));
        // Attempt numbers stay unique and monotonic, so the attempt log reads as one history - and
        // an exhausted delivery buys one more attempt per press rather than a fresh ladder.
        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(delivery.nextAttemptNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("a lease moves the due time without touching anything else")
    void lease() {
        WebhookDelivery delivery = delivery();

        delivery.lease(NOW.plusSeconds(30));

        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.attemptCount()).isZero();
    }
}
