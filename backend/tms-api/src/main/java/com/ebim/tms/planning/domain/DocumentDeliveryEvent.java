package com.ebim.tms.planning.domain;

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

/** One entry of a tracked document's append-only history (ADR-018, V57). */
@Entity
@Table(name = "document_delivery_event")
public class DocumentDeliveryEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "document_delivery_id", updatable = false, nullable = false)
    private UUID documentDeliveryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false)
    private DocumentDeliveryEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", updatable = false)
    private DocumentDeliveryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", updatable = false)
    private DocumentDeliveryReason reasonCode;

    @Column(name = "remark", updatable = false)
    private String remark;

    @Column(name = "reported_at", updatable = false)
    private OffsetDateTime reportedAt;

    @Column(name = "actor_id", updatable = false, nullable = false)
    private UUID actorId;

    @Column(name = "actor_label", updatable = false)
    private String actorLabel;

    @Column(name = "recorded_at", updatable = false, nullable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    protected DocumentDeliveryEvent() {
        // JPA
    }

    private DocumentDeliveryEvent(UUID companyId, UUID documentDeliveryId, DocumentDeliveryEventType eventType,
            DocumentDeliveryStatus status, DocumentDeliveryReason reasonCode, String remark,
            OffsetDateTime reportedAt, UUID actorId, String actorLabel, OffsetDateTime recordedAt,
            String idempotencyKey) {
        this.companyId = companyId;
        this.documentDeliveryId = documentDeliveryId;
        this.eventType = eventType;
        this.status = status;
        this.reasonCode = reasonCode;
        this.remark = remark;
        this.reportedAt = reportedAt;
        this.actorId = actorId;
        this.actorLabel = actorLabel;
        this.recordedAt = recordedAt;
        this.idempotencyKey = idempotencyKey;
    }

    public static DocumentDeliveryEvent reported(DocumentDelivery delivery, UUID actorId, String actorLabel,
            String idempotencyKey) {
        return new DocumentDeliveryEvent(delivery.companyId(), delivery.id(), DocumentDeliveryEventType.REPORTED,
                delivery.status(), delivery.reasonCode(), delivery.remark(), delivery.reportedAt(), actorId,
                actorLabel, delivery.recordedAt(), idempotencyKey);
    }

    public static DocumentDeliveryEvent returned(DocumentDelivery delivery, UUID actorId, String actorLabel) {
        return new DocumentDeliveryEvent(delivery.companyId(), delivery.id(), DocumentDeliveryEventType.RETURNED,
                null, null, delivery.returnRemark(), null, actorId, actorLabel, delivery.returnedAt(), null);
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID documentDeliveryId() {
        return documentDeliveryId;
    }

    public DocumentDeliveryEventType eventType() {
        return eventType;
    }

    public DocumentDeliveryStatus status() {
        return status;
    }

    public DocumentDeliveryReason reasonCode() {
        return reasonCode;
    }

    public String remark() {
        return remark;
    }

    public OffsetDateTime reportedAt() {
        return reportedAt;
    }

    public UUID actorId() {
        return actorId;
    }

    public String actorLabel() {
        return actorLabel;
    }

    public OffsetDateTime recordedAt() {
        return recordedAt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
