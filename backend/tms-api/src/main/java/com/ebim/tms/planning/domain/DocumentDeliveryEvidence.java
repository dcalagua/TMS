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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A photo or signature behind a document report (ADR-018, V57). The bytes live in ADR-006's private
 * store; this row is the proof that this company owns the key. Never deleted.
 */
@Entity
@Table(name = "document_delivery_evidence")
public class DocumentDeliveryEvidence {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "document_delivery_id", updatable = false, nullable = false)
    private UUID documentDeliveryId;

    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", updatable = false, nullable = false)
    private DocumentEvidenceType evidenceType;

    @Column(name = "storage_key", updatable = false, nullable = false)
    private String storageKey;

    @Column(name = "content_type", updatable = false, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", updatable = false, nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", updatable = false, nullable = false)
    private String checksumSha256;

    @Column(name = "original_filename", updatable = false)
    private String originalFilename;

    @Column(name = "captured_at", updatable = false)
    private OffsetDateTime capturedAt;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false, nullable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "uploaded_by", updatable = false, nullable = false)
    private UUID uploadedBy;

    protected DocumentDeliveryEvidence() {
        // JPA
    }

    public DocumentDeliveryEvidence(UUID companyId, UUID documentDeliveryId, UUID eventId,
            DocumentEvidenceType evidenceType, String storageKey, String contentType, long sizeBytes,
            String checksumSha256, String originalFilename, OffsetDateTime capturedAt, UUID uploadedBy) {
        this.companyId = companyId;
        this.documentDeliveryId = documentDeliveryId;
        this.eventId = eventId;
        this.evidenceType = evidenceType;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.originalFilename = originalFilename;
        this.capturedAt = capturedAt;
        this.uploadedBy = uploadedBy;
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

    public UUID eventId() {
        return eventId;
    }

    public DocumentEvidenceType evidenceType() {
        return evidenceType;
    }

    public String storageKey() {
        return storageKey;
    }

    public String contentType() {
        return contentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String checksumSha256() {
        return checksumSha256;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public OffsetDateTime capturedAt() {
        return capturedAt;
    }

    public OffsetDateTime uploadedAt() {
        return uploadedAt;
    }

    public UUID uploadedBy() {
        return uploadedBy;
    }
}
