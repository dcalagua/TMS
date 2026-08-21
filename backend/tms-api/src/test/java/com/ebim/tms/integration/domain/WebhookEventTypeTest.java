package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.domain.ShipmentEventType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one test that keeps two deliberately duplicated enums honest.
 *
 * <p>{@link WebhookEventType} cannot import {@code planning.domain.ShipmentEventType} - the module
 * boundary forbids it - so the vocabulary a customer subscribes to is written out a second time. A
 * test <em>can</em> see both, and this is what stops the duplication from becoming a divergence: a
 * value added to the outbox and forgotten here would otherwise mean every subscription to it
 * silently receives nothing, which is the worst possible failure mode for a webhook - it looks
 * exactly like "no events happened".
 *
 * <p>If this test fails, add the missing constant to {@link WebhookEventType} <em>and</em> to
 * {@code ck_webhook_subscription_event_type} in a new migration. The database has its own copy of
 * this list and it is not checked here, because a test that needs a database is a test that does not
 * run when Docker is unavailable.
 */
class WebhookEventTypeTest {

    @Test
    @DisplayName("every published shipment event type can be subscribed to")
    void coversEveryPublishedType() {
        List<String> published = Arrays.stream(ShipmentEventType.values()).map(Enum::name).toList();

        assertThat(WebhookEventType.names()).containsExactlyInAnyOrderElementsOf(published);
    }

    @Test
    @DisplayName("the two enums are declared in the same order, so the two lists read as one")
    void sameOrder() {
        List<String> published = Arrays.stream(ShipmentEventType.values()).map(Enum::name).toList();
        List<String> subscribable = Arrays.stream(WebhookEventType.values()).map(Enum::name).toList();

        assertThat(subscribable).isEqualTo(published);
    }

    @Test
    @DisplayName("an unknown name resolves to empty rather than throwing")
    void unknownNameIsEmpty() {
        // The fan-out asks this about every outbox row. Throwing would let a vocabulary gap fail a
        // trip confirmation, which is a business operation that has nothing to do with webhooks.
        assertThat(WebhookEventType.byName("SOMETHING_ELSE")).isEmpty();
        assertThat(WebhookEventType.byName(null)).isEmpty();
        assertThat(WebhookEventType.byName("SHIPMENT_CONFIRMED")).contains(WebhookEventType.SHIPMENT_CONFIRMED);
    }
}
