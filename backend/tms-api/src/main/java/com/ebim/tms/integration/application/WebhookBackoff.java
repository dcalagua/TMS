package com.ebim.tms.integration.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * When the next attempt at a delivery is due, and whether there is to be one at all (migration V35).
 *
 * <p>Pure, deterministic and separate from the dispatcher on purpose: "how long until we try again"
 * is the one part of a webhook sender that is worth reading in a test rather than inferred from
 * timestamps in a table.
 *
 * <h2>The schedule</h2>
 *
 * <p>Exponential from {@code retry-base-delay}, doubling, capped at {@code retry-max-delay}. With
 * the defaults - one minute, doubling, capped at thirty, six attempts - a delivery is tried at
 * roughly 0, 1, 3, 7, 15 and 45 minutes after the event, then given up on. That spans a normal
 * deployment window on the receiving side without turning a permanently dead endpoint into a week of
 * traffic.
 *
 * <h2>No jitter, deliberately</h2>
 *
 * <p>Jitter exists to stop a thundering herd of clients retrying in lockstep against one server.
 * Here the retrying clients are one dispatcher and the servers are as many as there are customers,
 * so there is no herd to spread; what jitter would cost is a schedule that cannot be asserted in a
 * test or explained to an integrator asking why their retry arrived when it did. If a single
 * subscription ever generates enough simultaneous deliveries for its own alignment to matter, the
 * fix is a per-subscription concurrency limit, not randomness.
 */
public final class WebhookBackoff {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public WebhookBackoff(WebhookProperties properties) {
        this(properties.maxAttempts(), properties.retryBaseDelay(), properties.retryMaxDelay());
    }

    public WebhookBackoff(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * When to try again after {@code attemptsMade} failed attempts, or empty when the schedule is
     * exhausted and the delivery is to be marked failed.
     *
     * @param attemptsMade how many attempts have already happened, the failed one included. The
     *     first failure passes 1
     */
    public Optional<OffsetDateTime> nextAttemptAt(int attemptsMade, OffsetDateTime now) {
        return delayAfter(attemptsMade).map(now::plus);
    }

    /** The delay itself, so a test can assert the ladder without arithmetic on timestamps. */
    public Optional<Duration> delayAfter(int attemptsMade) {
        if (attemptsMade < 1 || attemptsMade >= maxAttempts) {
            return Optional.empty();
        }
        // Doubling in seconds rather than by Duration.multipliedBy in a loop: the exponent is
        // bounded by ABSOLUTE_MAX_ATTEMPTS, so a long cannot overflow, and the cap is applied before
        // the value is ever used.
        long seconds = baseDelay.toSeconds() << (attemptsMade - 1);
        Duration delay = Duration.ofSeconds(seconds);
        return Optional.of(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }
}
