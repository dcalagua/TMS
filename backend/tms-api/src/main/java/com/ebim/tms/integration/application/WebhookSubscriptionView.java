package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookEventType;
import com.ebim.tms.integration.domain.WebhookSubscription;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The administrative view of one subscription.
 *
 * <p>Everything an operator needs to answer "is this endpoint working" - what it is, where it
 * points, what it wants, when it last succeeded, when it last failed and how many failures it is
 * currently carrying - and <b>nothing usable of the secret</b>: not the ciphertext, not the
 * algorithm's key, only {@link #secretHint}, four characters that identify which secret is
 * installed without being able to sign anything.
 *
 * @param secretHint the last four characters of the signing secret. Enough for "the one ending
 *     7fQ2", which is the question asked when two systems disagree about which secret is deployed
 * @param suspendedReason non-null only when TMS itself switched the subscription off after repeated
 *     failures, so a screen can say why rather than showing an endpoint that mysteriously stopped
 * @param consecutiveFailures the current streak, not a lifetime count. Zero the moment anything is
 *     delivered
 */
public record WebhookSubscriptionView(
        UUID id,
        String name,
        String description,
        String targetUrl,
        List<String> eventTypes,
        boolean active,
        String suspendedReason,
        String secretHint,
        OffsetDateTime secretRotatedAt,
        int consecutiveFailures,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastFailureAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static WebhookSubscriptionView from(WebhookSubscription subscription) {
        return new WebhookSubscriptionView(
                subscription.id(),
                subscription.name(),
                subscription.description(),
                subscription.targetUrl(),
                subscription.eventTypeValues().stream().map(WebhookEventType::name).toList(),
                subscription.active(),
                subscription.suspendedReason(),
                subscription.secretHint(),
                subscription.secretRotatedAt(),
                subscription.consecutiveFailures(),
                subscription.lastSuccessAt(),
                subscription.lastFailureAt(),
                subscription.createdAt(),
                subscription.updatedAt());
    }
}
