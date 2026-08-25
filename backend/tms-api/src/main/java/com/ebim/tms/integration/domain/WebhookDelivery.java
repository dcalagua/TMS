package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One published event owed to one subscription (migration V35).
 *
 * <p>Created in the same transaction as the outbox row it mirrors and delivered later, outside any
 * business transaction. Everything about its life is decided here rather than in the dispatcher, so
 * that "when does a delivery stop being retried" has one answer in one place instead of one per
 * call site.
 *
 * <h2>The payload is frozen</h2>
 *
 * <p>{@link #payload} is rendered once, at creation, and every attempt sends those exact bytes. A
 * body rebuilt per attempt would be a body that could change between attempt one and attempt six -
 * after a deployment, say - and a receiver comparing two deliveries of the same event id would be
 * looking at a discrepancy TMS created.
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "webhook_subscription_id", nullable = false, updatable = false)
    private WebhookSubscription subscription;

    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "event_type", updatable = false, nullable = false)
    private String eventType;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "payload", updatable = false, nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected WebhookDelivery() {
        // JPA
    }

    public WebhookDelivery(UUID companyId, WebhookSubscription subscription, UUID eventId,
            WebhookEventType eventType, OffsetDateTime occurredAt, String payload, OffsetDateTime dueAt) {
        this.companyId = companyId;
        this.subscription = subscription;
        this.eventId = eventId;
        this.eventType = eventType.name();
        this.occurredAt = occurredAt;
        this.payload = payload;
        this.nextAttemptAt = dueAt;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public WebhookSubscription subscription() {
        return subscription;
    }

    public UUID eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }

    public String payload() {
        return payload;
    }

    public WebhookDeliveryStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    /** What the attempt about to be made will be numbered. */
    public int nextAttemptNumber() {
        return attemptCount + 1;
    }

    public OffsetDateTime nextAttemptAt() {
        return nextAttemptAt;
    }

    public OffsetDateTime lastAttemptAt() {
        return lastAttemptAt;
    }

    public OffsetDateTime completedAt() {
        return completedAt;
    }

    public Integer lastStatusCode() {
        return lastStatusCode;
    }

    public String lastError() {
        return lastError;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /**
     * Records what one attempt came to and decides what happens next.
     *
     * @param retryAt when the next attempt is due, or {@code null} when there is to be none. The
     *     schedule is {@code WebhookBackoff}'s to compute and this method's only job is to apply
     *     it: a delivery that is out of retries and one whose receiver answered 400 both end
     *     {@link WebhookDeliveryStatus#FAILED}, and the difference between them is already recorded
     *     in the attempt row and in {@link #lastError}
     */
    public void recordAttempt(WebhookAttemptOutcome outcome, Integer statusCode, String error,
            OffsetDateTime now, OffsetDateTime retryAt) {
        this.attemptCount++;
        this.lastAttemptAt = now;
        this.lastStatusCode = statusCode;
        this.lastError = error;
        if (outcome == WebhookAttemptOutcome.DELIVERED) {
            this.status = WebhookDeliveryStatus.PROCESSED;
            this.completedAt = now;
            return;
        }
        if (retryAt == null) {
            this.status = WebhookDeliveryStatus.FAILED;
            this.completedAt = now;
            return;
        }
        this.status = WebhookDeliveryStatus.PENDING;
        this.nextAttemptAt = retryAt;
    }

    /**
     * Puts a finished delivery back in the queue - the operator's action once the receiving side
     * has been fixed.
     *
     * <p>{@link #attemptCount} is deliberately <em>not</em> reset, for two reasons. The attempt
     * numbers stay unique and monotonic, which is what keeps {@code tms.webhook_delivery_attempt}
     * readable as one history rather than as two overlapping ones. And a delivery whose schedule was
     * already exhausted therefore buys exactly one more attempt per press rather than a fresh ladder
     * of six against an endpoint that has already refused it six times - a person who knows their
     * side is fixed presses once and sees the answer, which is what they wanted; a person who is
     * guessing does not get an automatic flood.
     */
    public void requeue(OffsetDateTime now) {
        this.status = WebhookDeliveryStatus.PENDING;
        this.completedAt = null;
        this.nextAttemptAt = now;
    }

    /**
     * Holds this row out of the queue while an attempt at it is in flight.
     *
     * <p>The dispatcher claims a batch under {@code SELECT ... FOR UPDATE SKIP LOCKED}, pushes each
     * row's due time past the length of one attempt, and commits - so the database locks are
     * released before any HTTP call starts. Holding a transaction open across the network instead
     * would mean one slow receiver kept a lock for its whole timeout, which on a busy dispatcher is
     * how one bad endpoint takes the queue down with it.
     *
     * <p>A process that dies mid-attempt therefore loses nothing: the lease simply expires and the
     * next pass takes the row. The cost is that the delivery may be attempted twice, which the
     * contract already accounts for - deliveries are at-least-once and the receiver deduplicates on
     * {@link #eventId}.
     */
    public void lease(OffsetDateTime until) {
        this.nextAttemptAt = until;
    }

    public boolean isPending() {
        return status == WebhookDeliveryStatus.PENDING;
    }
}
