package com.ebim.tms.integration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed settings for outbound webhooks, under {@code tms.integration.webhooks} (migration V35).
 *
 * <h2>Off unless configured</h2>
 *
 * <p>There is no {@code enabled} flag. The feature is on exactly when {@link #secretKey} is set,
 * because without a key TMS cannot store a signing secret and therefore cannot have a subscription
 * to deliver to - a boolean beside it could only create a state where the feature says it is on and
 * every call fails. This is the same shape {@code EvidenceStorageProperties} uses: the capability is
 * present when the deployment has answered the question the capability cannot work without.
 *
 * @param secretKey the material subscription secrets are encrypted with. At least 32 characters of
 *     high-entropy random text from the deployment's secret store - <b>not</b> a passphrase, and
 *     <b>not</b> beside the connection string, since the two together are what would undo the
 *     encryption. Losing it does not lose the subscriptions, only their secrets: rotating each one
 *     re-issues a secret under the new key
 * @param maxAttempts how many times one delivery is tried before it is given up on. Six, with the
 *     schedule below, spans a little over an hour - long enough to ride out a deployment on the
 *     receiving side, short enough that a dead endpoint is visible on the screen rather than
 *     retried all week
 * @param retryBaseDelay the first retry's delay; each subsequent one doubles it until
 *     {@link #retryMaxDelay}
 * @param retryMaxDelay the ceiling on that doubling
 * @param requestTimeout how long one HTTP call may take. Deliberately short: a receiver that needs
 *     more than this is doing work inside the request instead of acknowledging and queueing, and
 *     the correct thing for TMS to do is to time out and retry rather than to hold a worker
 * @param batchSize how many due deliveries one dispatcher pass claims. A bound on the work of one
 *     tick, not a throughput setting - the tick simply runs again
 * @param pollInterval how often the dispatcher looks for due deliveries. The floor on how late a
 *     first delivery can be, and the reason it is seconds rather than minutes
 * @param suspendAfterConsecutiveFailures how many failures in a row switch a subscription off.
 *     Counts <em>exhausted deliveries</em> - each of which has already been through the whole retry
 *     ladder - and not individual attempts, which is the difference between "this endpoint has been
 *     dead for hours across many events" and "this endpoint was restarting during a deployment".
 *     Ten of them is a receiver nobody is going to fix in the next hour, and continuing to call it
 *     helps neither side. Any delivered attempt resets the streak to zero
 * @param allowInsecureTargets whether an {@code http://} target may be saved. False by default: a
 *     webhook body carries this company's shipment numbers and the signature is not encryption. A
 *     developer pointing at a local listener sets it, and the setting is named so that nobody sets
 *     it in production by accident
 * @param allowPrivateNetworkTargets whether a target may resolve to a loopback, link-local or
 *     private address. False by default, and this one is not a matter of taste: an administrator who
 *     can type a URL the server then fetches is a server-side request forgery primitive, and the
 *     interesting addresses are exactly the internal ones
 */
@ConfigurationProperties(prefix = "tms.integration.webhooks")
public record WebhookProperties(
        String secretKey,
        Integer maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Duration requestTimeout,
        Integer batchSize,
        Duration pollInterval,
        Integer suspendAfterConsecutiveFailures,
        Boolean allowInsecureTargets,
        Boolean allowPrivateNetworkTargets) {

    public static final int DEFAULT_MAX_ATTEMPTS = 6;
    public static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMinutes(1);
    public static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofMinutes(30);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_BATCH_SIZE = 50;
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(15);
    public static final int DEFAULT_SUSPEND_AFTER = 10;

    /** Past this a retry ladder stops being resilience and becomes a scheduled attack. */
    public static final int ABSOLUTE_MAX_ATTEMPTS = 12;

    /** Past this one dispatcher pass stops being a tick and becomes a batch job. */
    public static final int ABSOLUTE_MAX_BATCH_SIZE = 500;

    public WebhookProperties {
        secretKey = secretKey == null || secretKey.isBlank() ? null : secretKey.strip();
        maxAttempts = clamp(maxAttempts, DEFAULT_MAX_ATTEMPTS, 1, ABSOLUTE_MAX_ATTEMPTS);
        retryBaseDelay = positiveOr(retryBaseDelay, DEFAULT_RETRY_BASE_DELAY);
        retryMaxDelay = positiveOr(retryMaxDelay, DEFAULT_RETRY_MAX_DELAY);
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            // A ceiling below the first step would silently make every retry the same length.
            retryMaxDelay = retryBaseDelay;
        }
        requestTimeout = positiveOr(requestTimeout, DEFAULT_REQUEST_TIMEOUT);
        batchSize = clamp(batchSize, DEFAULT_BATCH_SIZE, 1, ABSOLUTE_MAX_BATCH_SIZE);
        pollInterval = positiveOr(pollInterval, DEFAULT_POLL_INTERVAL);
        suspendAfterConsecutiveFailures = clamp(suspendAfterConsecutiveFailures, DEFAULT_SUSPEND_AFTER, 1, 1000);
        allowInsecureTargets = allowInsecureTargets != null && allowInsecureTargets;
        allowPrivateNetworkTargets = allowPrivateNetworkTargets != null && allowPrivateNetworkTargets;
    }

    /** Whether this deployment can hold a signing secret, and therefore whether webhooks exist. */
    public boolean configured() {
        return secretKey != null;
    }

    private static int clamp(Integer requested, int fallback, int min, int max) {
        int value = requested == null ? fallback : requested;
        return Math.max(min, Math.min(value, max));
    }

    private static Duration positiveOr(Duration requested, Duration fallback) {
        return requested != null && !requested.isZero() && !requested.isNegative() ? requested : fallback;
    }
}
