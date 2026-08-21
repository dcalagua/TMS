package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One {@link WebhookEventType} one {@link WebhookSubscription} asked for (migration V35). A pure
 * child of its subscription, exactly like {@link IntegrationClientScope} is of its credential:
 * created and removed only through {@link WebhookSubscription#replaceEventTypes}, never persisted
 * on its own, and carrying no {@code company_id} because the tenant is the parent's.
 */
@Entity
@Table(name = "webhook_subscription_event")
public class WebhookSubscriptionEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "webhook_subscription_id", nullable = false, updatable = false)
    private WebhookSubscription subscription;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected WebhookSubscriptionEvent() {
        // JPA
    }

    WebhookSubscriptionEvent(WebhookSubscription subscription, WebhookEventType eventType) {
        this.subscription = subscription;
        this.eventType = eventType.name();
    }

    public UUID id() {
        return id;
    }

    public WebhookSubscription subscription() {
        return subscription;
    }

    /**
     * The type as a value, or empty when the row holds a name this build no longer knows - handled
     * rather than thrown for the reason {@link IntegrationClientScope#value()} gives: a vocabulary
     * term removed from the enum should cost that subscription one event type, not every read of it.
     */
    public Optional<WebhookEventType> value() {
        return WebhookEventType.byName(eventType);
    }

    public String name() {
        return eventType;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
