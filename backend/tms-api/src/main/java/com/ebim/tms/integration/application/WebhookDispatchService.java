package com.ebim.tms.integration.application;

import com.ebim.tms.integration.application.WebhookDeliveryQueue.ClaimedDelivery;
import com.ebim.tms.integration.domain.WebhookAttemptOutcome;
import com.ebim.tms.integration.domain.WebhookSecrets;
import com.ebim.tms.shared.web.CorrelationId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drains the delivery queue (migration V35): claims what is due, POSTs it, and records what
 * happened.
 *
 * <h2>Three phases, and none of them spans the network</h2>
 *
 * <ol>
 *   <li><b>Claim</b> - one short transaction ({@code WebhookDeliveryQueue.claim}) locks a batch of
 *       due rows with {@code SKIP LOCKED}, leases them past the length of one attempt, reads
 *       everything the send needs, and commits. Locks are gone before any socket is opened.</li>
 *   <li><b>Send</b> - no transaction at all. This is the only slow part and it holds nothing.</li>
 *   <li><b>Record</b> - one short transaction per delivery
 *       ({@code WebhookDeliveryQueue.record}).</li>
 * </ol>
 *
 * <p>The alternative - one transaction around the whole batch - is what a first draft of a webhook
 * sender usually looks like, and it means a receiver that accepts the connection and then goes quiet
 * holds a database lock for its entire timeout while every other delivery waits behind it.
 *
 * <h2>Running on more than one node</h2>
 *
 * <p>Safe without a leader election or an advisory lock: {@code SKIP LOCKED} hands two instances
 * disjoint batches, and the lease covers the window between claiming and recording. Nothing here
 * needs to know how many instances there are. The cost is that a node dying mid-attempt lets the
 * lease expire and the delivery be attempted again - which is why the contract is at-least-once and
 * the receiver deduplicates on the event id.
 *
 * <h2>What this class does not touch</h2>
 *
 * <p>No repository, no entity, no transaction. Everything here is a decision - which headers, which
 * signature, retryable or not, when next - so all of it can be tested against a stub sender and a
 * stub queue, which matters in an environment where a database is not always available.
 */
@Service
public class WebhookDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);

    /** Headers a receiver can route and deduplicate on without parsing the body first. */
    public static final String HEADER_EVENT_ID = "X-TMS-Event-Id";
    public static final String HEADER_EVENT_TYPE = "X-TMS-Event-Type";
    public static final String HEADER_DELIVERY_ID = "X-TMS-Delivery-Id";
    public static final String HEADER_ATTEMPT = "X-TMS-Delivery-Attempt";
    public static final String HEADER_SIGNATURE = "X-TMS-Signature";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    static final String USER_AGENT = "TMS-by-EBIM-Webhooks/1.0";

    private final WebhookDeliveryQueue queue;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final WebhookBackoff backoff;
    private final Clock clock;

    public WebhookDispatchService(WebhookDeliveryQueue queue, WebhookTargetPolicy targetPolicy, WebhookSender sender,
            WebhookProperties properties, Clock clock) {
        this.queue = queue;
        this.targetPolicy = targetPolicy;
        this.sender = sender;
        this.properties = properties;
        this.backoff = new WebhookBackoff(properties);
        this.clock = clock;
    }

    /**
     * One pass of the dispatcher.
     *
     * <p>Runs under a correlation id of its own, which reaches the receiver as
     * {@code X-Correlation-Id} and every log line of the pass - so a partner quoting one value can
     * be answered from the log rather than from a timestamp and a guess.
     *
     * @return how many deliveries were attempted
     */
    public int dispatchDue() {
        if (!properties.configured()) {
            return 0;
        }
        return CorrelationId.withNewTrace(() -> {
            List<ClaimedDelivery> claimed = queue.claim();
            if (claimed.isEmpty()) {
                return 0;
            }
            log.debug("Dispatching {} due webhook deliveries", claimed.size());
            claimed.forEach(this::attempt);
            return claimed.size();
        });
    }

    private void attempt(ClaimedDelivery claimed) {
        OffsetDateTime sentAt = OffsetDateTime.now(clock);

        // Re-checked here and not only when the subscription was saved: a hostname that resolved
        // publicly then and resolves to an internal address now is exactly the attack this closes.
        // See WebhookTargetPolicy for what it can and cannot promise.
        String refusal = targetPolicy.rejectionReasonAtSendTime(claimed.targetUrl());
        WebhookSendResult result;
        WebhookAttemptOutcome outcome;
        if (refusal != null) {
            result = WebhookSendResult.failed(refusal, 0);
            // Permanent rather than retryable: the URL will not become acceptable by waiting, and
            // an operator has to change it. Retrying would re-resolve the same name every minute.
            outcome = WebhookAttemptOutcome.PERMANENT_FAILURE;
        } else {
            result = sender.send(claimed.targetUrl(), claimed.payload(), headersFor(claimed, sentAt));
            outcome = result.outcome();
        }

        OffsetDateTime retryAt = outcome.isRetryable()
                ? backoff.nextAttemptAt(claimed.attemptNumber(), OffsetDateTime.now(clock)).orElse(null)
                : null;
        queue.record(claimed, result, outcome, sentAt, retryAt);
    }

    /**
     * The headers of one attempt. {@code X-TMS-Delivery-Attempt} is included so a receiver can tell
     * a retry from a first delivery in its own log without querying us about it.
     */
    Map<String, String> headersFor(ClaimedDelivery claimed, OffsetDateTime sentAt) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_EVENT_ID, claimed.eventId().toString());
        headers.put(HEADER_EVENT_TYPE, claimed.eventType());
        headers.put(HEADER_DELIVERY_ID, claimed.deliveryId().toString());
        headers.put(HEADER_ATTEMPT, Integer.toString(claimed.attemptNumber()));
        headers.put(HEADER_SIGNATURE, WebhookSecrets.signatureHeader(claimed.secret(), sentAt, claimed.payload()));
        headers.put("User-Agent", USER_AGENT);
        CorrelationId.current().ifPresent(value -> headers.put(HEADER_CORRELATION_ID, value));
        return headers;
    }
}
