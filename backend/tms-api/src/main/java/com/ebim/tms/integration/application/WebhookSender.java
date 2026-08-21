package com.ebim.tms.integration.application;

import java.util.Map;

/**
 * The one thing in the webhook feature that touches the network (migration V35).
 *
 * <p>A port with a single implementation, and not because a second one is expected: it exists so
 * that {@code WebhookDispatchService} - which decides retries, suspensions and what is written to
 * the attempt log - can be tested against a stub instead of against a socket. Every interesting rule
 * in this feature lives on the other side of this interface, and none of them should need an HTTP
 * server to assert.
 *
 * <p>Implementations must not throw for a network failure. A refused connection is an ordinary
 * outcome of a webhook, not an exception, and the dispatcher has to record it as an attempt either
 * way; an implementation that threw would make the two failure paths different and one of them
 * untested.
 */
public interface WebhookSender {

    /**
     * POSTs {@code payload} to {@code targetUrl} and reports what happened.
     *
     * @param headers the signature and the delivery metadata, already computed. The implementation
     *     adds only the content type
     */
    WebhookSendResult send(String targetUrl, String payload, Map<String, String> headers);
}
