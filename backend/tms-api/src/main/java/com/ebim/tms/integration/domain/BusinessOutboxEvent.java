package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One row of {@code tms.business_outbox_event} (migration V61): a published fact that is not about a
 * trip - {@code ORDER_CANCELLED} today. Written in the same transaction as the change it records, and
 * never changed: its id is the event id every webhook receiver deduplicates on.
 */
@Entity
@Table(name = "business_outbox_event")
public class BusinessOutboxEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "resource_type", updatable = false, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false, nullable = false)
    private UUID resourceId;

    @Column(name = "resource_reference", updatable = false, nullable = false)
    private String resourceReference;

    @Column(name = "event_type", updatable = false, nullable = false)
    private String eventType;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    protected BusinessOutboxEvent() {
        // JPA
    }

    public BusinessOutboxEvent(UUID companyId, String resourceType, UUID resourceId, String resourceReference,
            String eventType, OffsetDateTime occurredAt) {
        this.companyId = companyId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceReference = resourceReference;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String resourceType() {
        return resourceType;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public String resourceReference() {
        return resourceReference;
    }

    public String eventType() {
        return eventType;
    }

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }
}
