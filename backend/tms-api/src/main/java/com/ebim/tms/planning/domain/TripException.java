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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * An operational problem on a trip or at one of its stops, OPEN until somebody deals with it
 * (migration V27).
 *
 * <p>The one mutable table of the three V27 adds, and the only thing that earns it that: OPEN /
 * RESOLVED answers "what went wrong today that nobody has closed out", which an append-only log
 * cannot - a REPORTED entry and a RESOLVED entry are two rows, and asking whether the second
 * exists for every first is a self-join a supervisor screen would run on every refresh.
 *
 * <p>Every skipped or failed stop has one, opened by {@code TripStopExecutionService} in the same
 * transaction as the stop transition. That is what makes "why did this delivery not happen"
 * answerable, and it is why {@link StopExecutionStatus#requiresException()} exists.
 *
 * <p>Resolving it is the whole workflow. There is no assignment, severity or escalation model,
 * because no rule in TMS reads one - see {@link TripExceptionStatus}.
 */
@Entity
@Table(name = "trip_exception")
public class TripException {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    /** The stop this is about, or null when the problem is the trip's. */
    @Column(name = "trip_stop_id", updatable = false)
    private UUID tripStopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", updatable = false, nullable = false)
    private TripExceptionType exceptionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TripExceptionStatus status;

    @Column(name = "reported_at", updatable = false, nullable = false)
    private OffsetDateTime reportedAt;

    @Column(name = "reported_by", updatable = false, nullable = false)
    private UUID reportedBy;

    @Column(name = "notes", updatable = false)
    private String notes;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected TripException() {
        // JPA
    }

    /**
     * Reports a problem. Always OPEN to begin with: an exception nobody has looked at is the
     * default and the point, and "reported and already resolved" is two actions, not one.
     *
     * @param reportedAt when the problem happened, operator-supplied, not when it was typed
     */
    public TripException(UUID companyId, UUID tripId, UUID tripStopId, TripExceptionType exceptionType,
            OffsetDateTime reportedAt, UUID reportedBy, String notes) {
        if (exceptionType.requiresStop() && tripStopId == null) {
            throw new IllegalArgumentException(exceptionType + " is about a delivery and must name a stop");
        }
        this.companyId = companyId;
        this.tripId = tripId;
        this.tripStopId = tripStopId;
        this.exceptionType = exceptionType;
        this.status = TripExceptionStatus.OPEN;
        this.reportedAt = reportedAt;
        this.reportedBy = reportedBy;
        this.notes = notes;
    }

    /**
     * Closes the exception out.
     *
     * <p>An {@link IllegalStateException} on a second call, not a silent no-op: {@code
     * TripExceptionService} answers a retry with the already-resolved row before it gets here, so
     * reaching this means a caller skipped that check - and the honest answer to a defect is a
     * rolled-back transaction rather than a resolution time quietly rewritten to a later one.
     */
    public void resolve(OffsetDateTime resolvedAt, UUID resolvedBy, String resolutionNotes) {
        if (status == TripExceptionStatus.RESOLVED) {
            throw new IllegalStateException("exception " + id + " is already resolved");
        }
        this.status = TripExceptionStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
        this.resolutionNotes = resolutionNotes;
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

    public UUID tripStopId() {
        return tripStopId;
    }

    public TripExceptionType exceptionType() {
        return exceptionType;
    }

    public TripExceptionStatus status() {
        return status;
    }

    public boolean isOpen() {
        return status == TripExceptionStatus.OPEN;
    }

    public OffsetDateTime reportedAt() {
        return reportedAt;
    }

    public UUID reportedBy() {
        return reportedBy;
    }

    public String notes() {
        return notes;
    }

    public OffsetDateTime resolvedAt() {
        return resolvedAt;
    }

    public UUID resolvedBy() {
        return resolvedBy;
    }

    public String resolutionNotes() {
        return resolutionNotes;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
