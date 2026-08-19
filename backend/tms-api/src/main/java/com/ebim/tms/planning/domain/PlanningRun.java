package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One manual planning session for a company, an origin and a planning date (migration V11): the
 * container the {@link Trip}s of that day hang from. See
 * {@code docs/domain/PLANNING_MANUAL_V1.md} for the lifecycle and
 * {@code docs/database/DATA_MODEL.md} section 14 for the schema decisions.
 *
 * <p>Owns no collection: trips are a separate aggregate reached by {@code planningRunId}, not a
 * JPA {@code @OneToMany} - loading a run must never drag in every trip, every stop and every
 * assignment of a 300-trip day (the step brief's "avoid loading 10,000 order lines for a planning
 * board summary"). {@code TripRepository} fetches exactly the projection each screen needs.
 *
 * <p>Holds no counters or totals either: unlike {@code TransportOrder}'s header totals - which
 * exist because the backend is the sole writer of the lines they summarise - a run's trip and
 * order counts change through a different aggregate on almost every request, so a stored counter
 * would be a drift risk in exchange for one grouped query. See
 * {@code docs/database/DATA_MODEL.md} section 14.2.
 */
@Entity
@Table(name = "planning_run")
public class PlanningRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "plan_number", updatable = false, nullable = false)
    private String planNumber;

    @Column(name = "origin_id", updatable = false, nullable = false)
    private UUID originId;

    @Column(name = "planning_date", updatable = false, nullable = false)
    private LocalDate planningDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", updatable = false, nullable = false)
    private PlanningMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlanningRunStatus status;

    @Column(name = "notes")
    private String notes;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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

    protected PlanningRun() {
        // JPA
    }

    public PlanningRun(UUID companyId, String planNumber, UUID originId, LocalDate planningDate, String notes,
            UUID actorId) {
        this.companyId = companyId;
        this.planNumber = planNumber;
        this.originId = originId;
        this.planningDate = planningDate;
        this.mode = PlanningMode.MANUAL;
        this.status = PlanningRunStatus.DRAFT;
        this.notes = notes;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String planNumber() {
        return planNumber;
    }

    public UUID originId() {
        return originId;
    }

    public LocalDate planningDate() {
        return planningDate;
    }

    public PlanningMode mode() {
        return mode;
    }

    public PlanningRunStatus status() {
        return status;
    }

    public boolean isDraft() {
        return status == PlanningRunStatus.DRAFT;
    }

    public String notes() {
        return notes;
    }

    public OffsetDateTime confirmedAt() {
        return confirmedAt;
    }

    public UUID confirmedBy() {
        return confirmedBy;
    }

    public OffsetDateTime cancelledAt() {
        return cancelledAt;
    }

    public UUID cancelledBy() {
        return cancelledBy;
    }

    public String cancelReason() {
        return cancelReason;
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

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public void updateNotes(String notes, UUID actorId) {
        this.notes = notes;
        this.updatedBy = actorId;
    }

    /**
     * Legality of this transition - and validating every trip in the run before it - is
     * {@code PlanningRunService.confirm}'s concern, not this entity's, exactly as
     * {@code TransportOrder.markReadyForPlanning} leaves its own guard to {@code OrderService}.
     */
    public void confirm(UUID actorId) {
        this.status = PlanningRunStatus.CONFIRMED;
        this.confirmedAt = OffsetDateTime.now();
        this.confirmedBy = actorId;
        this.updatedBy = actorId;
    }

    /** Legality of this transition is {@code PlanningRunService.cancel}'s concern. */
    public void cancel(String reason, UUID actorId) {
        this.status = PlanningRunStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        this.cancelledBy = actorId;
        this.cancelReason = reason;
        this.updatedBy = actorId;
    }
}
