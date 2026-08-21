package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retry ladder, pinned.
 *
 * <p>"When will you try again" is the question an integrator asks during an incident, and the answer
 * is published in {@code docs/integrations/WEBHOOKS_V1.md}. It is a pure function precisely so it can
 * be asserted here rather than inferred from timestamps in a table hours later.
 */
class WebhookBackoffTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);

    private final WebhookBackoff backoff =
            new WebhookBackoff(6, Duration.ofMinutes(1), Duration.ofMinutes(30));

    @Test
    @DisplayName("the delay doubles from the base delay")
    void doubles() {
        assertThat(backoff.delayAfter(1)).contains(Duration.ofMinutes(1));
        assertThat(backoff.delayAfter(2)).contains(Duration.ofMinutes(2));
        assertThat(backoff.delayAfter(3)).contains(Duration.ofMinutes(4));
        assertThat(backoff.delayAfter(4)).contains(Duration.ofMinutes(8));
    }

    @Test
    @DisplayName("the delay is capped, so a long ladder does not become a day")
    void capped() {
        // Doubling would give 16 minutes here and 32 at the next step; the cap holds it at 30.
        assertThat(backoff.delayAfter(5)).contains(Duration.ofMinutes(16));
        assertThat(new WebhookBackoff(12, Duration.ofMinutes(1), Duration.ofMinutes(30)).delayAfter(7))
                .contains(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("the schedule is exhausted once the last attempt has been made")
    void exhausted() {
        // Six attempts means five retries: after the sixth there is nothing left to schedule, and
        // the delivery is marked FAILED.
        assertThat(backoff.delayAfter(5)).isPresent();
        assertThat(backoff.delayAfter(6)).isEmpty();
        assertThat(backoff.delayAfter(7)).isEmpty();
    }

    @Test
    @DisplayName("an attempt count below one is not a schedule at all")
    void nonsenseInput() {
        assertThat(backoff.delayAfter(0)).isEmpty();
        assertThat(backoff.delayAfter(-1)).isEmpty();
    }

    @Test
    @DisplayName("the next attempt is the delay applied to the caller's clock")
    void nextAttemptIsRelativeToNow() {
        assertThat(backoff.nextAttemptAt(2, NOW)).contains(NOW.plusMinutes(2));
        assertThat(backoff.nextAttemptAt(6, NOW)).isEmpty();
    }

    @Test
    @DisplayName("the whole ladder spans about an hour with the shipped defaults")
    void shippedDefaults() {
        WebhookBackoff shipped = new WebhookBackoff(new WebhookProperties(
                "x".repeat(32), null, null, null, null, null, null, null, null, null));

        // 1 + 2 + 4 + 8 + 16 minutes of waiting across five retries: long enough to ride out a
        // deployment on the receiving side, short enough that a dead endpoint is visible today.
        assertThat(shipped.maxAttempts()).isEqualTo(WebhookProperties.DEFAULT_MAX_ATTEMPTS);
        assertThat(shipped.delayAfter(1)).contains(Duration.ofMinutes(1));
        assertThat(shipped.delayAfter(5)).contains(Duration.ofMinutes(16));
        assertThat(shipped.delayAfter(6)).isEmpty();
    }

    @Test
    @DisplayName("a ceiling configured below the first step does not flatten the ladder into nothing")
    void ceilingBelowBaseIsCorrected() {
        WebhookProperties properties = new WebhookProperties("x".repeat(32), 4, Duration.ofMinutes(5),
                Duration.ofMinutes(1), null, null, null, null, null, null);

        // Without the correction in the record's constructor, every retry would be one minute and
        // the configured base delay would silently mean nothing.
        assertThat(properties.retryMaxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new WebhookBackoff(properties).delayAfter(1)).contains(Duration.ofMinutes(5));
    }
}
