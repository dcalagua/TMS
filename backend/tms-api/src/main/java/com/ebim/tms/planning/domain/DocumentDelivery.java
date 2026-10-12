package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One logistics document on one trip (ADR-018, V57): what the carrier reported about it and whether
 * the office has the signed copy back.
 *
 * <p>Created {@link DocumentDeliveryStatus#PENDING} when the trip's warehouse dispatch arrives, with
 * a snapshot of the document's type and number - both part of ADR-015's unique key, so they cannot
 * drift. The carrier may correct a report until the office confirms the return; after that the row
 * is closed. Every change is also an append-only {@link DocumentDeliveryEvent}.
 *
 * <p>Nothing here moves an order, a stop or a trip.
 */
@Entity
@Table(name = "document_delivery")
public class DocumentDelivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "document_id", updatable = false, nullable = false)
    private UUID documentId;

    @Column(name = "document_type", updatable = false, nullable = false)
    private String documentType;

    @Column(name = "document_number", updatable = false, nullable = false)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DocumentDeliveryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private DocumentDeliveryReason reasonCode;

    @Column(name = "remark")
    private String remark;

    @Column(name = "reported_at")
    private OffsetDateTime reportedAt;

    @Column(name = "recorded_at")
    private OffsetDateTime recordedAt;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "recorded_by_label")
    private String recordedByLabel;

    @Column(name = "returned_at")
    private OffsetDateTime returnedAt;

    @Column(name = "returned_by")
    private UUID returnedBy;

    @Column(name = "returned_by_label")
    private String returnedByLabel;

    @Column(name = "return_remark")
    private String returnRemark;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DocumentDelivery() {
        // JPA
    }

    public DocumentDelivery(UUID companyId, UUID tripId, UUID documentId, String documentType, String documentNumber) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.documentId = documentId;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.status = DocumentDeliveryStatus.PENDING;
    }

    /** Whether the carrier may still report or correct: until the office confirms the return. */
    public boolean isOpenForReport() {
        return returnedAt == null;
    }

    public boolean isReturned() {
        return returnedAt != null;
    }

    /**
     * Records what the carrier says happened. The caller has validated the combination; the
     * database checks it again ({@code ck_document_delivery_reason_by_status}).
     */
    public void report(DocumentDeliveryStatus outcome, DocumentDeliveryReason reason, String remark,
            OffsetDateTime reportedAt, OffsetDateTime recordedAt, UUID actorId, String actorLabel) {
        if (!isOpenForReport()) {
            throw new IllegalStateException("A returned document cannot be reported again.");
        }
        if (outcome == DocumentDeliveryStatus.PENDING) {
            throw new IllegalArgumentException("PENDING is not a report.");
        }
        this.status = outcome;
        this.reasonCode = outcome.requiresReason() ? reason : null;
        this.remark = remark;
        this.reportedAt = reportedAt;
        this.recordedAt = recordedAt;
        this.recordedBy = actorId;
        this.recordedByLabel = actorLabel;
    }

    /** The office has the signed copy (or the undelivered original) back. Closes the row. */
    public void confirmReturn(OffsetDateTime at, UUID actorId, String actorLabel, String remark) {
        if (status == DocumentDeliveryStatus.PENDING) {
            throw new IllegalStateException("A pending document cannot be returned.");
        }
        if (isReturned()) {
            throw new IllegalStateException("The return is already confirmed.");
        }
        this.returnedAt = at;
        this.returnedBy = actorId;
        this.returnedByLabel = actorLabel;
        this.returnRemark = remark;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID tripId() {
        return tripId;
    }

    public UUID documentId() {
        return documentId;
    }

    public String documentType() {
        return documentType;
    }

    public String documentNumber() {
        return documentNumber;
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

    public OffsetDateTime recordedAt() {
        return recordedAt;
    }

    public UUID recordedBy() {
        return recordedBy;
    }

    public String recordedByLabel() {
        return recordedByLabel;
    }

    public OffsetDateTime returnedAt() {
        return returnedAt;
    }

    public UUID returnedBy() {
        return returnedBy;
    }

    public String returnedByLabel() {
        return returnedByLabel;
    }

    public String returnRemark() {
        return returnRemark;
    }

    public long version() {
        return version;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
