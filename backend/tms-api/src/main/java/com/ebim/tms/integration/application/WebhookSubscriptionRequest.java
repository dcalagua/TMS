package com.ebim.tms.integration.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Create and update share one shape, following every other module's convention.
 *
 * <p>What a caller cannot say here, and why:
 *
 * <ul>
 *   <li><b>No company.</b> The subscription is created inside the administrator's selected company
 *       scope. A body that named a company would be a client-supplied tenant.</li>
 *   <li><b>No secret.</b> Generated server-side from a CSPRNG, like every other secret in TMS. A
 *       chosen signing key is a key somebody chose to be memorable.</li>
 *   <li><b>No active flag.</b> Turning an endpoint on and off is its own action with its own audit
 *       row, not a field somebody flips while renaming it - which is exactly how a suspension gets
 *       silently undone by a save from a stale screen.</li>
 * </ul>
 *
 * @param eventTypes the names of {@code WebhookEventType}. {@code @NotEmpty} on purpose: a
 *     subscription selecting nothing receives nothing, so it is not a quiet subscription, it is a
 *     misconfiguration that looks like one. "Everything" is spelled by selecting every type
 * @param targetUrl validated by {@code WebhookTargetPolicy}, which is a security control and not a
 *     format check - see its class comment
 */
public record WebhookSubscriptionRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 2048) String targetUrl,
        @NotEmpty(message = "a subscription must select at least one event type")
        Set<@NotBlank String> eventTypes) {

    public WebhookSubscriptionRequest {
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
    }
}
