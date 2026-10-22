package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A photo or document behind a freight claim (migration V71). Metadata only, the shape of
 * {@code DeliveryEvidence} (V28): the bytes live behind {@code EvidenceStoragePort}. Append-only -
 * every column is insert-only, and the database withholds UPDATE and DELETE.
 */
@Entity
@Table(name = "freight_claim_evidence")
public class FreightClaimEvidence {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "freight_claim_id", updatable = false, nullable = false)
    private UUID freightClaimId;

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

    @Column(name = "description", updatable = false)
    private String description;

    @Column(name = "uploaded_by", updatable = false, nullable = false)
    private UUID uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected FreightClaimEvidence() {
        // JPA
    }

    public FreightClaimEvidence(UUID companyId, UUID freightClaimId, String storageKey, String contentType,
            long sizeBytes, String checksumSha256, String originalFilename, String description, UUID uploadedBy) {
        this.companyId = companyId;
        this.freightClaimId = freightClaimId;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.originalFilename = originalFilename;
        this.description = description;
        this.uploadedBy = uploadedBy;
    }

    public UUID id() {
        return id;
    }

    public UUID freightClaimId() {
        return freightClaimId;
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

    public String originalFilename() {
        return originalFilename;
    }

    public String description() {
        return description;
    }

    public UUID uploadedBy() {
        return uploadedBy;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
