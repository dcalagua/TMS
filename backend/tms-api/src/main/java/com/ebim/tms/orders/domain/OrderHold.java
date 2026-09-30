package com.ebim.tms.orders.domain;

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
 * A hold on a transport order (migration V54, ADR-014 section 7).
 *
 * <p><b>Separate from the order's status, on purpose.</b> A hold never moves {@code OrderStatus}: an
 * order held for credit is still {@code NOT_READY} or {@code READY_FOR_PLANNING}, and lifting the hold
 * returns it to exactly where it was. While it is active and {@link #blocking()}, the order cannot be
 * released and is not a planning candidate; on an order already on a trip it blocks the trip's
 * dispatch instead of silently unplanning anything.
 *
 * <p>Lifted, never deleted - who stopped an order and who let it go is the history a disputed late
 * delivery turns on. Its own aggregate, reached through {@code OrderHoldRepository} and never hung
 * off {@link TransportOrder}, so placing a hold does not bump the order's version under a planner.
 */
@Entity
@Table(name = "order_hold")
public class OrderHold {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "order_id", updatable = false, nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hold_type", updatable = false, nullable = false)
    private HoldType holdType;

    @Column(name = "reason_code", updatable = false)
    private String reasonCode;

    @Column(name = "reason", updatable = false, nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", updatable = false, nullable = false)
    private HoldSource source;

    @Column(name = "blocking", updatable = false, nullable = false)
    private boolean blocking;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_client", updatable = false)
    private UUID createdByClient;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_by_client")
    private UUID releasedByClient;

    @Column(name = "release_reason")
    private String releaseReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected OrderHold() {
        // JPA
    }

    /** A hold a person placed in the TMS UI. */
    public static OrderHold byOperator(UUID companyId, UUID orderId, HoldType holdType, String reasonCode,
            String reason, boolean blocking, UUID appUserId) {
        if (appUserId == null) {
            throw new IllegalArgumentException("an operator hold names the person who placed it");
        }
        OrderHold hold = new OrderHold(companyId, orderId, holdType, reasonCode, reason, blocking);
        hold.source = HoldSource.OPERATOR;
        hold.createdBy = appUserId;
        return hold;
    }

    private OrderHold(UUID companyId, UUID orderId, HoldType holdType, String reasonCode, String reason,
            boolean blocking) {
        this.companyId = companyId;
        this.orderId = orderId;
        this.holdType = holdType;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.blocking = blocking;
    }

    public boolean isActive() {
        return releasedAt == null;
    }

    /** Active and blocking: the only kind that stops release, planning and dispatch. */
    public boolean isActiveBlocking() {
        return isActive() && blocking;
    }

    /**
     * Lifts the hold. Refused when it has already been lifted - a second release would overwrite who
     * let the order go, which is the one fact this row exists to keep.
     */
    public void releaseByOperator(String releaseReason, UUID appUserId, OffsetDateTime at) {
        if (!isActive()) {
            throw new IllegalStateException("this hold has already been released");
        }
        this.releasedAt = at;
        this.releasedBy = appUserId;
        this.releaseReason = releaseReason;
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

    public HoldType holdType() {
        return holdType;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String reason() {
        return reason;
    }

    public HoldSource source() {
        return source;
    }

    public boolean blocking() {
        return blocking;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID createdByClient() {
        return createdByClient;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime releasedAt() {
        return releasedAt;
    }

    public UUID releasedBy() {
        return releasedBy;
    }

    public UUID releasedByClient() {
        return releasedByClient;
    }

    public String releaseReason() {
        return releaseReason;
    }

    public long version() {
        return version;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
