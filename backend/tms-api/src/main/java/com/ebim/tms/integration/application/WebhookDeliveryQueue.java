package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookAttemptOutcome;
import com.ebim.tms.integration.domain.WebhookDelivery;
import com.ebim.tms.integration.domain.WebhookDeliveryAttempt;
import com.ebim.tms.integration.domain.WebhookSubscription;
import com.ebim.tms.integration.infrastructure.WebhookDeliveryAttemptRepository;
import com.ebim.tms.integration.infrastructure.WebhookDeliveryRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of the dispatcher (migration V35): claiming what is due, and recording what came
 * of it.
 *
 * <h2>Why this is its own bean</h2>
 *
 * <p>Because {@code @Transactional} is applied by a proxy, and a method a class calls on itself does
 * not go through one. Leaving {@link #claim()} and {@link #record} on {@code WebhookDispatchService}
 * would have produced code that reads as transactional, is not, and fails in the one situation the
 * transactions exist for. Splitting them also leaves {@code WebhookDispatchService} with no
 * persistence at all, which is what lets its retry, signature and suspension behaviour be tested
 * against stubs instead of against a database.
 *
 * <p>The transaction boundaries are the design, not an implementation detail - see
 * {@code WebhookDispatchService} for the three-phase shape and why no transaction may span the send.
 */
@Service
public class WebhookDeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryQueue.class);

    /** How far out a delivery for a switched-off subscription is pushed, on top of the lease. */
    private static final int INACTIVE_BACKOFF_MINUTES = 5;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookSecretVault secretVault;
    private final WebhookProperties properties;
    private final Clock clock;

    public WebhookDeliveryQueue(WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryAttemptRepository attemptRepository, WebhookSecretVault secretVault,
            WebhookProperties properties, Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.secretVault = secretVault;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Phase one: lock a batch of due rows with {@code SKIP LOCKED}, lease them past the length of
     * one attempt, and copy out everything the send will need. Commits before any socket is opened.
     *
     * <p>A delivery whose subscription has been switched off since the fan-out is left
     * {@code PENDING} and pushed further out rather than failed. Leaving it queued is what makes
     * reactivating an endpoint deliver the backlog; failing it would throw away events the customer
     * has not decided anything about.
     */
    @Transactional
    public List<ClaimedDelivery> claim() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Long enough that an attempt cannot outlive its own lease - twice the request timeout, plus
        // a minute for the recording transaction - and short enough that a crashed node's rows come
        // back quickly.
        OffsetDateTime leaseUntil = now.plus(properties.requestTimeout().multipliedBy(2)).plusMinutes(1);

        List<WebhookDelivery> due = deliveryRepository.claimDue(now, PageRequest.of(0, properties.batchSize()));
        List<ClaimedDelivery> claimed = new ArrayList<>(due.size());
        for (WebhookDelivery delivery : due) {
            WebhookSubscription subscription = delivery.subscription();
            if (!subscription.active()) {
                delivery.lease(leaseUntil.plusMinutes(INACTIVE_BACKOFF_MINUTES));
                continue;
            }
            delivery.lease(leaseUntil);
            claimed.add(new ClaimedDelivery(
                    delivery.id(),
                    delivery.companyId(),
                    subscription.id(),
                    subscription.name(),
                    subscription.targetUrl(),
                    secretVault.reveal(subscription),
                    delivery.eventId(),
                    delivery.eventType(),
                    delivery.payload(),
                    delivery.nextAttemptNumber()));
        }
        return claimed;
    }

    /**
     * Phase three: write the attempt row, apply the outcome to the delivery, and update the
     * subscription's failure streak.
     *
     * <p>{@code REQUIRES_NEW} and one call per delivery: a failure recording one must not roll back
     * the record of the twenty already sent in the same pass.
     *
     * @param retryAt when the next attempt is due, or null when there is to be none. Computed by
     *     {@code WebhookBackoff} in the caller, so that "when do we try again" stays a pure function
     *     of the attempt number rather than something only observable in a table
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ClaimedDelivery claimed, WebhookSendResult result, WebhookAttemptOutcome outcome,
            OffsetDateTime sentAt, OffsetDateTime retryAt) {
        Optional<WebhookDelivery> found = deliveryRepository.findById(claimed.deliveryId());
        if (found.isEmpty()) {
            // Not reachable through any API - deliveries are never deleted - but a row removed by a
            // retention job while it was in flight must not take the dispatcher down with it.
            log.warn("Webhook delivery {} disappeared while it was being sent", claimed.deliveryId());
            return;
        }
        WebhookDelivery delivery = found.get();
        OffsetDateTime now = OffsetDateTime.now(clock);

        attemptRepository.save(new WebhookDeliveryAttempt(claimed.companyId(), delivery.id(),
                claimed.attemptNumber(), sentAt, result.durationMs(), result.statusCode(), outcome,
                result.errorSummary()));

        delivery.recordAttempt(outcome, result.statusCode(), result.errorSummary(), now, retryAt);

        WebhookSubscription subscription = delivery.subscription();
        if (outcome == WebhookAttemptOutcome.DELIVERED) {
            subscription.recordSuccess(now);
            return;
        }
        if (delivery.isPending()) {
            // Still owed. The streak counts deliveries that have run out of attempts, not attempts,
            // so nothing is recorded against the subscription until this one is finished.
            return;
        }
        int streak = subscription.recordFailure(now);
        if (streak >= properties.suspendAfterConsecutiveFailures()) {
            subscription.suspend("Suspended automatically after " + streak
                    + " consecutive deliveries failed. Reactivate once the endpoint is reachable again.");
            log.warn("Webhook subscription {} ({}) suspended after {} consecutive failed deliveries",
                    subscription.id(), claimed.subscriptionName(), streak);
        }
    }

    /**
     * Everything one attempt needs, copied out of the persistence context so that the send can
     * happen with no transaction and no lock held.
     *
     * <p>It carries a decrypted signing secret. It is therefore never logged, never returned from a
     * controller and never put in a view - it exists between {@link #claim()} and the sender and
     * nowhere else.
     */
    public record ClaimedDelivery(
            UUID deliveryId,
            UUID companyId,
            UUID subscriptionId,
            String subscriptionName,
            String targetUrl,
            String secret,
            UUID eventId,
            String eventType,
            String payload,
            int attemptNumber) {
    }
}
