package com.ebim.tms.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A customer tracking link of one transport order (migration V74, ADR-029).
 *
 * <p>Holds the SHA-256 of its token, never the token. Its identity - company, order, hash, hard expiry,
 * creator - is fixed at creation ({@code updatable = false} here, and {@code tr_tracking_link_guard} in
 * the database against the owner too). After that only two things change: it is revoked, once, and its
 * view counter moves (through {@code TrackingLinkRepository.recordView}, which leaves the version alone
 * so that two customers opening the link at once never conflict with an office revocation).
 *
 * <p>Revoked, never deleted: "who shared this order with whom, and who took it back" is the trail a
 * leaked link is investigated with.
 */
@Entity
@Table(name = "tracking_link")
public class TrackingLink {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "order_id", updatable = false, nullable = false)
    private UUID orderId;

    @Column(name = "token_hash", updatable = false, nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", updatable = false, nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", updatable = false, nullable = false)
    private UUID createdBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "last_viewed_at", insertable = false, updatable = false)
    private OffsetDateTime lastViewedAt;

    @Column(name = "view_count", insertable = false, updatable = false)
    private long viewCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected TrackingLink() {
        // JPA
    }

    /**
     * A link a person generated. {@code createdAt} is the caller's clock rather than the database's so
     * that the hard expiry and the creation instant come from the same reading.
     */
    public static TrackingLink issue(UUID companyId, UUID orderId, TrackingToken token, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, UUID createdBy) {
        Objects.requireNonNull(token, "token");
        if (createdBy == null) {
            throw new IllegalArgumentException("a tracking link names the person who generated it");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a tracking link must expire after it is created");
        }
        TrackingLink link = new TrackingLink();
        link.companyId = Objects.requireNonNull(companyId, "companyId");
        link.orderId = Objects.requireNonNull(orderId, "orderId");
        link.tokenHash = token.hash();
        link.createdAt = createdAt;
        link.expiresAt = expiresAt;
        link.createdBy = createdBy;
        return link;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Revokes the link. Refused when it already is - a second revocation would overwrite who took it
     * back, which is the one fact this row exists to keep.
     */
    public void revoke(String reason, UUID appUserId, OffsetDateTime at) {
        if (isRevoked()) {
            throw new IllegalStateException("this tracking link has already been revoked");
        }
        this.revokedAt = Objects.requireNonNull(at, "at");
        this.revokedBy = Objects.requireNonNull(appUserId, "appUserId");
        this.revokeReason = Objects.requireNonNull(reason, "reason");
    }

    /** The earlier of the hard cap and the soft end after the order closed (ADR-029 T3). */
    public OffsetDateTime effectiveExpiry(OffsetDateTime closedAt, java.time.Duration afterClosure) {
        if (closedAt == null) {
            return expiresAt;
        }
        OffsetDateTime soft = closedAt.plus(afterClosure);
        return soft.isBefore(expiresAt) ? soft : expiresAt;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID orderId() {
        return orderId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public UUID revokedBy() {
        return revokedBy;
    }

    public String revokeReason() {
        return revokeReason;
    }

    public OffsetDateTime lastViewedAt() {
        return lastViewedAt;
    }

    public long viewCount() {
        return viewCount;
    }

    public long version() {
        return version;
    }
}
