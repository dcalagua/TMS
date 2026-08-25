package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The subscription's own rules, without a database. */
class WebhookSubscriptionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID ACTOR = UUID.randomUUID();

    private WebhookSubscription subscription() {
        return new WebhookSubscription(UUID.randomUUID(), "WMS", null, "https://partner.example/hooks",
                "ciphertext", "7fQ2", ACTOR);
    }

    @Test
    @DisplayName("replacing the event types keeps the rows of the ones that were already selected")
    void replaceIsADiff() {
        WebhookSubscription subscription = subscription();
        subscription.replaceEventTypes(
                List.of(WebhookEventType.SHIPMENT_CONFIRMED, WebhookEventType.SHIPMENT_CANCELLED), ACTOR);
        WebhookSubscriptionEvent kept = subscription.eventTypeRows().stream()
                .filter(row -> row.value().orElseThrow() == WebhookEventType.SHIPMENT_CONFIRMED)
                .findFirst()
                .orElseThrow();

        subscription.replaceEventTypes(
                List.of(WebhookEventType.SHIPMENT_CONFIRMED, WebhookEventType.SHIPMENT_COMPLETED), ACTOR);

        // The unchanged selection must keep its own row, so its created_at keeps answering "since
        // when has this endpoint been receiving confirmations".
        assertThat(subscription.eventTypeRows()).contains(kept);
        assertThat(subscription.eventTypeValues()).containsExactlyInAnyOrder(
                WebhookEventType.SHIPMENT_CONFIRMED, WebhookEventType.SHIPMENT_COMPLETED);
        assertThat(subscription.wants(WebhookEventType.SHIPMENT_CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("selecting nothing leaves a subscription that wants nothing")
    void emptySelection() {
        WebhookSubscription subscription = subscription();
        subscription.replaceEventTypes(Set.of(WebhookEventType.SHIPMENT_CONFIRMED), ACTOR);

        subscription.replaceEventTypes(Set.of(), ACTOR);

        // The entity allows it; the request record's @NotEmpty is what stops it being reachable
        // through the API, because a subscription that receives nothing looks exactly like a
        // working one that has had no events.
        assertThat(subscription.eventTypeValues()).isEmpty();
    }

    @Test
    @DisplayName("a delivered attempt clears the failure streak")
    void successResetsTheStreak() {
        WebhookSubscription subscription = subscription();
        subscription.recordFailure(NOW);
        subscription.recordFailure(NOW);

        subscription.recordSuccess(NOW.plusMinutes(1));

        assertThat(subscription.consecutiveFailures()).isZero();
        assertThat(subscription.lastSuccessAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(subscription.lastFailureAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("suspension is the dispatcher switching the endpoint off, with a reason")
    void suspension() {
        WebhookSubscription subscription = subscription();

        subscription.suspend("Suspended automatically after 10 consecutive deliveries failed.");

        assertThat(subscription.active()).isFalse();
        assertThat(subscription.suspendedReason()).startsWith("Suspended automatically");
    }

    @Test
    @DisplayName("reactivating clears both the reason and the streak")
    void reactivation() {
        WebhookSubscription subscription = subscription();
        subscription.recordFailure(NOW);
        subscription.recordFailure(NOW);
        subscription.suspend("dead endpoint");

        subscription.setActive(true, ACTOR);

        assertThat(subscription.active()).isTrue();
        assertThat(subscription.suspendedReason()).isNull();
        // Without this, the very next failure would suspend it again and reactivation would look
        // like it had not worked.
        assertThat(subscription.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a person pausing an endpoint leaves no automatic reason behind")
    void pausingIsNotSuspension() {
        WebhookSubscription subscription = subscription();
        subscription.suspend("dead endpoint");

        subscription.setActive(false, ACTOR);

        assertThat(subscription.active()).isFalse();
        assertThat(subscription.suspendedReason()).isNull();
    }

    @Test
    @DisplayName("rotating replaces the secret outright: there is no grace window")
    void rotation() {
        WebhookSubscription subscription = subscription();

        subscription.rotateSecret("new-ciphertext", "aB3-", NOW, ACTOR);

        assertThat(subscription.secretCiphertext()).isEqualTo("new-ciphertext");
        assertThat(subscription.secretHint()).isEqualTo("aB3-");
        assertThat(subscription.secretRotatedAt()).isEqualTo(NOW);
        assertThat(subscription.secretAlgorithm()).isEqualTo(WebhookSecretCipher.ALGORITHM);
    }
}
