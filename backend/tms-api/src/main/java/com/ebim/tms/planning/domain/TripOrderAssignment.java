package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The explicit assignment of one order to one {@link Trip} (migration V11), and the reason there
 * is no {@code trip_id} column on {@code transport_order}:
 *
 * <ol>
 *   <li>it carries the <em>allocated</em> load ({@code assignedWeightKg}/{@code assignedVolumeM3}/
 *       {@code assignedPallets}), so a future partial assignment is a second row with smaller
 *       numbers rather than a schema change - capacity already sums these columns and never the
 *       order header, so splitting an order changes no capacity code;</li>
 *   <li>it keeps history: {@link #close} closes the current assignment instead of deleting it, so
 *       a reassignment leaves both the old and the new record behind;</li>
 *   <li>its {@code ACTIVE + wholeOrder} shape is guarded by a partial unique index, which is what
 *       actually stops two concurrent planners from assigning the same order twice - see
 *       {@code docs/domain/PLANNING_MANUAL_V1.md}, "Concurrency".</li>
 * </ol>
 *
 * <p>References its trip by plain {@code tripId}, not a JPA association (the same choice
 * {@code Vehicle} makes for its carrier and type): a trip must be loadable without its
 * assignments, and an assignment readable without materialising its trip.
 *
 * <p>Has no {@code version} column, unlike {@link Trip} and {@link PlanningRun}: a row here is
 * inserted once and closed once, never edited in a form a second user could hold a stale copy of.
 * The concurrency that matters happens between rows, and the partial unique index covers it.
 */
@Entity
@Table(name = "trip_order_assignment")
public class TripOrderAssignment {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "order_id", updatable = false, nullable = false)
    private UUID orderId;

    @Column(name = "whole_order", updatable = false, nullable = false)
    private boolean wholeOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AssignmentStatus status;

    @Column(name = "assigned_weight_kg", updatable = false, nullable = false, precision = 14, scale = 3)
    private BigDecimal assignedWeightKg;

    @Column(name = "assigned_volume_m3", updatable = false, nullable = false, precision = 14, scale = 4)
    private BigDecimal assignedVolumeM3;

    @Column(name = "assigned_pallets", updatable = false, nullable = false, precision = 12, scale = 2)
    private BigDecimal assignedPallets;

    @Column(name = "assigned_at", updatable = false, nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    @Column(name = "removed_by")
    private UUID removedBy;

    @Column(name = "removal_reason")
    private String removalReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected TripOrderAssignment() {
        // JPA
    }

    public TripOrderAssignment(UUID companyId, UUID tripId, UUID orderId, BigDecimal assignedWeightKg,
            BigDecimal assignedVolumeM3, BigDecimal assignedPallets, UUID actorId) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.orderId = orderId;
        // Always true in V1: the UI assigns whole orders. The column is what keeps the partial
        // unique index from blocking a future split allocation.
        this.wholeOrder = true;
        this.status = AssignmentStatus.ACTIVE;
        this.assignedWeightKg = assignedWeightKg;
        this.assignedVolumeM3 = assignedVolumeM3;
        this.assignedPallets = assignedPallets;
        this.assignedAt = OffsetDateTime.now();
        this.assignedBy = actorId;
        this.createdBy = actorId;
        this.updatedBy = actorId;
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

    public UUID orderId() {
        return orderId;
    }

    public boolean wholeOrder() {
        return wholeOrder;
    }

    public AssignmentStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == AssignmentStatus.ACTIVE;
    }

    public BigDecimal assignedWeightKg() {
        return assignedWeightKg;
    }

    public BigDecimal assignedVolumeM3() {
        return assignedVolumeM3;
    }

    public BigDecimal assignedPallets() {
        return assignedPallets;
    }

    public OffsetDateTime assignedAt() {
        return assignedAt;
    }

    public UUID assignedBy() {
        return assignedBy;
    }

    public OffsetDateTime removedAt() {
        return removedAt;
    }

    public UUID removedBy() {
        return removedBy;
    }

    public String removalReason() {
        return removalReason;
    }

    /**
     * Closes this assignment, preserving it as history. The row stops counting towards the trip's
     * load the moment its status leaves {@code ACTIVE} - every capacity query filters on it.
     */
    public void close(String reason, UUID actorId) {
        this.status = AssignmentStatus.REMOVED;
        this.removedAt = OffsetDateTime.now();
        this.removedBy = actorId;
        this.removalReason = reason;
        this.updatedBy = actorId;
    }
}
