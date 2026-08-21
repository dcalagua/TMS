package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.integration.application.WebhookDeliveryQueue.ClaimedDelivery;
import com.ebim.tms.integration.domain.WebhookAttemptOutcome;
import com.ebim.tms.integration.domain.WebhookSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The dispatcher's decisions, without a database and without a socket.
 *
 * <p>Everything that decides whether a partner's system is told about a shipment - which headers go
 * out, what a response means, when the next attempt is due, whether a stored URL is still allowed -
 * is asserted here. That was the reason for splitting the persistence into
 * {@code WebhookDeliveryQueue}: this behaviour has to be verifiable on a machine with no Docker.
 */
class WebhookDispatchServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String SECRET = "tmsw_" + "a".repeat(43);
    private static final String PAYLOAD = "{\"apiVersion\":\"v1\",\"id\":\"e\"}";

    private final Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private WebhookDeliveryQueue queue;
    private WebhookSender sender;
    private WebhookDispatchService service;

    private static WebhookProperties configured() {
        return new WebhookProperties("x".repeat(32), 3, Duration.ofMinutes(1), Duration.ofMinutes(30),
                null, null, null, null, false, true);
    }

    @BeforeEach
    void setUp() {
        queue = mock(WebhookDeliveryQueue.class);
        sender = mock(WebhookSender.class);
        service = new WebhookDispatchService(queue, new WebhookTargetPolicy(configured()), sender,
                configured(), clock);
    }

    private static ClaimedDelivery claimed(int attemptNumber) {
        return claimed(attemptNumber, "https://partner.example/hooks");
    }

    private static ClaimedDelivery claimed(int attemptNumber, String targetUrl) {
        return new ClaimedDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "WMS",
                targetUrl, SECRET, UUID.randomUUID(), "SHIPMENT_CONFIRMED", PAYLOAD, attemptNumber);
    }

    @Test
    @DisplayName("a deployment with no signing key does not even read the queue")
    void disabledDeploymentDoesNothing() {
        WebhookProperties unconfigured =
                new WebhookProperties(null, null, null, null, null, null, null, null, null, null);
        WebhookDispatchService disabled = new WebhookDispatchService(queue,
                new WebhookTargetPolicy(unconfigured), sender, unconfigured, clock);

        assertThat(disabled.dispatchDue()).isZero();

        verifyNoInteractions(queue);
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("an empty queue costs one read and no sends")
    void nothingDue() {
        when(queue.claim()).thenReturn(List.of());

        assertThat(service.dispatchDue()).isZero();

        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("a 2xx records a delivery with no next attempt")
    void delivered() {
        ClaimedDelivery delivery = claimed(1);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(204, 42));

        assertThat(service.dispatchDue()).isEqualTo(1);

        verify(queue).record(eq(delivery), any(), eq(WebhookAttemptOutcome.DELIVERED), eq(NOW), isNull());
    }

    @Test
    @DisplayName("a 503 is retried, and the next attempt follows the ladder")
    void retryable() {
        ClaimedDelivery delivery = claimed(2);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(503, 10));

        service.dispatchDue();

        // Second attempt failed, so the third is due one doubling after the base delay.
        verify(queue).record(eq(delivery), any(), eq(WebhookAttemptOutcome.RETRYABLE_FAILURE), eq(NOW),
                eq(NOW.plusMinutes(2)));
    }

    @Test
    @DisplayName("the last attempt in the schedule leaves no next attempt, so the delivery fails")
    void scheduleExhausted() {
        ClaimedDelivery delivery = claimed(3);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(500, 10));

        service.dispatchDue();

        // max-attempts is 3 in this test's configuration.
        verify(queue).record(eq(delivery), any(), eq(WebhookAttemptOutcome.RETRYABLE_FAILURE), eq(NOW), isNull());
    }

    @Test
    @DisplayName("a 400 is never retried")
    void permanentFailure() {
        ClaimedDelivery delivery = claimed(1);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(400, 10));

        service.dispatchDue();

        verify(queue).record(eq(delivery), any(), eq(WebhookAttemptOutcome.PERMANENT_FAILURE), eq(NOW), isNull());
    }

    @Test
    @DisplayName("a transport failure is retryable, because nothing reached a server at all")
    void transportFailure() {
        ClaimedDelivery delivery = claimed(1);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WebhookSendResult.failed("HttpConnectTimeoutException", 10_000));

        service.dispatchDue();

        verify(queue).record(eq(delivery), any(), eq(WebhookAttemptOutcome.RETRYABLE_FAILURE), eq(NOW),
                eq(NOW.plusMinutes(1)));
    }

    @Test
    @DisplayName("a stored URL that no longer passes the policy is refused instead of fetched")
    void targetIsRecheckedBeforeSending() {
        // A hostname re-pointed at an internal address after the subscription was saved. This
        // service is configured to refuse http, so the stored value fails the re-check.
        ClaimedDelivery delivery = claimed(1, "http://partner.example/hooks");
        when(queue.claim()).thenReturn(List.of(delivery));

        service.dispatchDue();

        verify(sender, never()).send(anyString(), anyString(), any());
        verify(queue).record(eq(delivery), any(), eq(WebhookAttemptOutcome.PERMANENT_FAILURE), eq(NOW), isNull());
    }

    @Test
    @DisplayName("every delivery in a batch is attempted, even when one of them fails")
    void wholeBatchIsAttempted() {
        ClaimedDelivery first = claimed(1);
        ClaimedDelivery second = claimed(1);
        when(queue.claim()).thenReturn(List.of(first, second));
        when(sender.send(anyString(), anyString(), any()))
                .thenReturn(WebhookSendResult.responded(500, 10))
                .thenReturn(WebhookSendResult.responded(200, 10));

        assertThat(service.dispatchDue()).isEqualTo(2);

        verify(queue).record(eq(first), any(), eq(WebhookAttemptOutcome.RETRYABLE_FAILURE), any(), any());
        verify(queue).record(eq(second), any(), eq(WebhookAttemptOutcome.DELIVERED), any(), isNull());
    }

    @Test
    @DisplayName("the headers carry the event, the delivery, the attempt and a verifiable signature")
    void headers() {
        ClaimedDelivery delivery = claimed(3);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(200, 5));

        service.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(sender).send(eq("https://partner.example/hooks"), eq(PAYLOAD), headers.capture());

        Map<String, String> sent = headers.getValue();
        assertThat(sent).containsEntry(WebhookDispatchService.HEADER_EVENT_ID, delivery.eventId().toString());
        assertThat(sent).containsEntry(WebhookDispatchService.HEADER_EVENT_TYPE, "SHIPMENT_CONFIRMED");
        assertThat(sent).containsEntry(WebhookDispatchService.HEADER_DELIVERY_ID, delivery.deliveryId().toString());
        // A receiver can tell a retry from a first delivery without asking us about it.
        assertThat(sent).containsEntry(WebhookDispatchService.HEADER_ATTEMPT, "3");
        // The pass runs under a correlation id of its own, and the receiver is told what it is.
        assertThat(sent.get(WebhookDispatchService.HEADER_CORRELATION_ID)).isNotBlank();

        String signature = sent.get(WebhookDispatchService.HEADER_SIGNATURE);
        assertThat(signature).matches("^t=\\d+,v1=[0-9a-f]{64}$");
        assertThat(WebhookSecrets.matches(SECRET, NOW.toEpochSecond(), PAYLOAD,
                signature.substring(signature.indexOf("v1=") + 3))).isTrue();
    }

    @Test
    @DisplayName("the signature is over the exact bytes that were sent")
    void signatureCoversTheSentBody() {
        ClaimedDelivery delivery = claimed(1);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(200, 5));

        service.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(anyString(), body.capture(), headers.capture());

        String signature = headers.getValue().get(WebhookDispatchService.HEADER_SIGNATURE);
        String hex = signature.substring(signature.indexOf("v1=") + 3);
        // Verifying against a different body must fail, or the signature would be decoration.
        assertThat(WebhookSecrets.matches(SECRET, NOW.toEpochSecond(), body.getValue(), hex)).isTrue();
        assertThat(WebhookSecrets.matches(SECRET, NOW.toEpochSecond(), body.getValue() + " ", hex)).isFalse();
    }

    @Test
    @DisplayName("the timestamp in the signature is the moment of this attempt")
    void signatureTimestampIsTheAttempt() {
        ClaimedDelivery delivery = claimed(1);
        when(queue.claim()).thenReturn(List.of(delivery));
        when(sender.send(anyString(), anyString(), any())).thenReturn(WebhookSendResult.responded(200, 5));

        service.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(sender).send(anyString(), anyString(), headers.capture());

        // Not the event's own occurredAt, which may be hours older: a receiver's replay window is
        // about how long ago the bytes were put on the wire.
        assertThat(headers.getValue().get(WebhookDispatchService.HEADER_SIGNATURE))
                .startsWith("t=" + Instant.from(NOW).getEpochSecond() + ",");
    }
}
