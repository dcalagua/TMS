package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.application.WebhookProperties;
import com.ebim.tms.integration.application.WebhookSendResult;
import com.ebim.tms.integration.application.WebhookSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The webhook feature's HTTP client, and the product's first outbound one (migration V35).
 *
 * <h2>Choices worth naming</h2>
 *
 * <ul>
 *   <li><b>{@link HttpClient.Redirect#NEVER}.</b> A redirect on a signed POST would re-send this
 *       company's data, with its signature, to a location no administrator approved - the receiver
 *       re-opening the hole {@code WebhookTargetPolicy} exists to close. A 3xx is recorded as a
 *       permanent failure and the operator fixes the URL.</li>
 *   <li><b>The response body is discarded.</b> {@code BodyHandlers.discarding()} means an endpoint
 *       that answers with a megabyte of HTML costs nothing, and - more importantly - nothing a
 *       receiver writes can reach a TMS log or an error field. What TMS records is the status
 *       line.</li>
 *   <li><b>Two timeouts.</b> One on the connect, one on the whole exchange. Without the second, a
 *       receiver that accepts the connection and then goes quiet holds the thread until the OS
 *       gives up, which on a busy dispatcher is how one bad endpoint starves every other.</li>
 *   <li><b>One shared client.</b> It owns a connection pool and is thread-safe; building one per
 *       send would discard the pool and re-do every TLS handshake.</li>
 * </ul>
 *
 * <p>Nothing here throws for a network failure - see {@link WebhookSender}. Every exception the JDK
 * can raise for one is turned into a {@code WebhookSendResult} carrying a short, sanitised reason.
 */
@Component
public class HttpWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookSender.class);

    private static final int MAX_ERROR_LENGTH = 200;

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpWebhookSender(WebhookProperties properties) {
        this.requestTimeout = properties.requestTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public WebhookSendResult send(String targetUrl, String payload, Map<String, String> headers) {
        long startedAt = System.nanoTime();
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            // The URL was validated when the subscription was saved, so this is a stored value that
            // has since become unusable. An attempt row saying so is more useful than an exception
            // in a log nobody is reading.
            return WebhookSendResult.failed("target URL is not usable", elapsedMillis(startedAt));
        }
        headers.forEach(request::header);

        try {
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return WebhookSendResult.responded(response.statusCode(), elapsedMillis(startedAt));
        } catch (IOException failure) {
            // Timeouts, refused connections, TLS failures, unresolvable hosts. The class name plus a
            // truncated message: enough for an operator to tell a certificate problem from a
            // firewall, without copying an arbitrary string into a table.
            log.debug("Webhook delivery to {} failed at the transport layer", targetUrl, failure);
            return WebhookSendResult.failed(summarise(failure), elapsedMillis(startedAt));
        } catch (InterruptedException interrupted) {
            // Shutdown. Restore the flag and report a retryable failure: the delivery is still
            // PENDING and the next dispatcher pass - in this process or another - will take it.
            Thread.currentThread().interrupt();
            return WebhookSendResult.failed("delivery was interrupted", elapsedMillis(startedAt));
        }
    }

    private static int elapsedMillis(long startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String summarise(IOException failure) {
        String message = failure.getMessage();
        String summary = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return summary.length() <= MAX_ERROR_LENGTH ? summary : summary.substring(0, MAX_ERROR_LENGTH);
    }
}
