package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One HTTP call the dispatcher made to a subscriber's endpoint (migration V35).
 *
 * <p>Immutable once written, like {@code ShipmentOutboxEvent} and {@code IntegrationRequest}: the
 * row is a record of something that already happened and there is no correcting it afterwards.
 *
 * <p>{@code webhookDeliveryId} is a plain column rather than a {@code @ManyToOne}. The attempt is
 * written from the dispatcher, which already holds the delivery it is working on, and a mapped
 * association would only offer a second way to navigate back to a row the caller has in hand -
 * along with the lazy-loading question that comes with it.
 */
@Entity
@Table(name = "webhook_delivery_attempt")
public class WebhookDeliveryAttempt {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "webhook_delivery_id", updatable = false, nullable = false)
    private UUID webhookDeliveryId;

    @Column(name = "attempt_number", updatable = false, nullable = false)
    private int attemptNumber;

    @Column(name = "attempted_at", updatable = false, nullable = false)
    private OffsetDateTime attemptedAt;

    @Column(name = "duration_ms", updatable = false, nullable = false)
    private int durationMs;

    /** Null when no response was ever produced: DNS failure, refused connection, timeout. */
    @Column(name = "status_code", updatable = false)
    private Integer statusCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false, nullable = false)
    private WebhookAttemptOutcome outcome;

    /** Sanitised and short. Never a response body - see the column comment in V35. */
    @Column(name = "error", updatable = false)
    private String error;

    protected WebhookDeliveryAttempt() {
        // JPA
    }

    public WebhookDeliveryAttempt(UUID companyId, UUID webhookDeliveryId, int attemptNumber,
            OffsetDateTime attemptedAt, int durationMs, Integer statusCode, WebhookAttemptOutcome outcome,
            String error) {
        this.companyId = companyId;
        this.webhookDeliveryId = webhookDeliveryId;
        this.attemptNumber = attemptNumber;
        this.attemptedAt = attemptedAt;
        this.durationMs = durationMs;
        this.statusCode = statusCode;
        this.outcome = outcome;
        this.error = error;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID webhookDeliveryId() {
        return webhookDeliveryId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public OffsetDateTime attemptedAt() {
        return attemptedAt;
    }

    public int durationMs() {
        return durationMs;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public WebhookAttemptOutcome outcome() {
        return outcome;
    }

    public String error() {
        return error;
    }
}
