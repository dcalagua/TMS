package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which HTTP answers are worth trying again.
 *
 * <p>Getting this wrong is expensive in both directions: treating a 500 as permanent loses events
 * during someone's deployment, and treating a 400 as retryable turns a misconfigured receiver into
 * a scheduled flood against their server.
 */
class WebhookAttemptOutcomeTest {

    @Test
    @DisplayName("any 2xx is a delivery")
    void success() {
        assertThat(WebhookAttemptOutcome.forStatus(200)).isEqualTo(WebhookAttemptOutcome.DELIVERED);
        assertThat(WebhookAttemptOutcome.forStatus(201)).isEqualTo(WebhookAttemptOutcome.DELIVERED);
        // 204 is what a well-behaved receiver answers: acknowledged, nothing to say.
        assertThat(WebhookAttemptOutcome.forStatus(204)).isEqualTo(WebhookAttemptOutcome.DELIVERED);
        assertThat(WebhookAttemptOutcome.forStatus(299)).isEqualTo(WebhookAttemptOutcome.DELIVERED);
    }

    @Test
    @DisplayName("a 5xx is retryable: the receiver may yet recover")
    void serverErrorsRetry() {
        assertThat(WebhookAttemptOutcome.forStatus(500)).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(502)).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(503)).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
    }

    @Test
    @DisplayName("the three timing 4xx are retryable, and the rest are not")
    void clientErrors() {
        assertThat(WebhookAttemptOutcome.forStatus(408)).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(425)).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);
        // A receiver asking for later must be given later, or the ones being polite about load are
        // exactly the ones that get given up on.
        assertThat(WebhookAttemptOutcome.forStatus(429)).isEqualTo(WebhookAttemptOutcome.RETRYABLE_FAILURE);

        assertThat(WebhookAttemptOutcome.forStatus(400)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(401)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(404)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("410 Gone means stop sending here")
    void goneIsPermanent() {
        // Continuing to retry after being told this is what gets a sender blocked outright.
        assertThat(WebhookAttemptOutcome.forStatus(410)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("a redirect is a permanent failure, because a signed POST is never followed")
    void redirectsAreNotFollowed() {
        // Following one would re-send this company's data, with its signature, to a location no
        // administrator approved - the SSRF control being re-opened by the receiver.
        assertThat(WebhookAttemptOutcome.forStatus(301)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(302)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
        assertThat(WebhookAttemptOutcome.forStatus(307)).isEqualTo(WebhookAttemptOutcome.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("only RETRYABLE_FAILURE is retried")
    void retryablePredicate() {
        assertThat(WebhookAttemptOutcome.RETRYABLE_FAILURE.isRetryable()).isTrue();
        assertThat(WebhookAttemptOutcome.PERMANENT_FAILURE.isRetryable()).isFalse();
        assertThat(WebhookAttemptOutcome.DELIVERED.isRetryable()).isFalse();
    }
}
