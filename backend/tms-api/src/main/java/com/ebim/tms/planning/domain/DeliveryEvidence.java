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
 * One proof-of-delivery artefact attached to an {@link OrderDelivery} (migration V28) - a
 * signature, a photograph, or a document.
 *
 * <p><b>Metadata only.</b> The bytes live in a private object store behind
 * {@code shared.storage.EvidenceStoragePort}; this row holds the key that finds them, what they
 * are, how big they are and what they hash to. There is no content column and there is no URL:
 * a permanent public link to a customer's signed delivery note is a data leak with a stable
 * address, so bytes are served only by {@code TripDeliveryController}, which re-checks the company
 * scope and the permission on every read.
 *
 * <p><b>Append-only.</b> No setter, and the database withholds {@code UPDATE} and {@code DELETE}
 * from {@code tms_app} - the same treatment {@code tms.audit_event} and {@link TransportEvent}
 * get, and for a sharper reason: evidence a party can quietly edit is not evidence. A wrong photo
 * is answered by uploading the right one beside it.
 *
 * <p>{@code storageKey} is opaque and server-generated. It is never derived from the file name the
 * caller sent, which is what makes path traversal impossible here rather than filtered.
 */
@Entity
@Table(name = "delivery_evidence")
public class DeliveryEvidence {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "order_delivery_id", updatable = false, nullable = false)
    private UUID orderDeliveryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", updatable = false, nullable = false)
    private EvidenceType evidenceType;

    @Column(name = "storage_key", updatable = false, nullable = false)
    private String storageKey;

    @Column(name = "content_type", updatable = false, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", updatable = false, nullable = false)
    private long sizeBytes;

    /**
     * SHA-256 of the stored bytes, lower-case hex. Not a security control - the store is trusted -
     * but the answer to "is the file we are showing in a dispute the one that was uploaded", which
     * cannot be answered later if nobody computed it at the time.
     */
    @Column(name = "checksum_sha256", updatable = false, nullable = false)
    private String checksumSha256;

    /** What the operator's device called it. Display only; never used to build a path. */
    @Column(name = "original_filename", updatable = false)
    private String originalFilename;

    /** When the photo was taken or the signature captured, when that is known to differ from the upload. */
    @Column(name = "captured_at", updatable = false)
    private OffsetDateTime capturedAt;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false, nullable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "uploaded_by", updatable = false, nullable = false)
    private UUID uploadedBy;

    protected DeliveryEvidence() {
        // JPA
    }

    /**
     * Registers an artefact that has <em>already</em> been written to the object store.
     *
     * <p>The order matters and is the service's responsibility: bytes first, row second. A row
     * pointing at an object that was never stored would be a piece of evidence that cannot be
     * produced, which is worse than no row - while an object with no row is merely an orphan the
     * store can be swept for.
     */
    public DeliveryEvidence(UUID companyId, UUID orderDeliveryId, EvidenceType evidenceType, String storageKey,
            String contentType, long sizeBytes, String checksumSha256, String originalFilename,
            OffsetDateTime capturedAt, UUID uploadedBy) {
        this.companyId = companyId;
        this.orderDeliveryId = orderDeliveryId;
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

    public UUID orderDeliveryId() {
        return orderDeliveryId;
    }

    public EvidenceType evidenceType() {
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
