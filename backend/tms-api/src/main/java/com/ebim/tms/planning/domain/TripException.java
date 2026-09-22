package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
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
     * Whether an incoming report says the same thing this row already says, and is therefore the
     * same problem rather than a second one.
     *
     * <p>The whole duplicate rule, in one place, because it is read from two doors: a dispatcher
     * writing a problem up by hand ({@code TripExceptionService.report}) and a stop being skipped
     * or failed ({@code TripStopExecutionService}), which opens one automatically. Two identical
     * statements about the same unresolved problem - a double click, a retried request, the same
     * fact entered through both doors - are one problem, and a second row for it would make the
     * control tower count two and bury the one that is real.
     *
     * <p><b>Same stop, same type, same sentence.</b> The stop and the type are what the problem is
     * about; the sentence is what it says. A report carrying <em>different</em> notes is new
     * information and is deliberately <b>not</b> matched here: {@code notes} is immutable on this
     * row, so folding it in would be losing what it said, which is worse than a second row.
     *
     * <p><b>The time is not part of the key</b>, and cannot be: a second click is a second reading
     * of the clock. The first report's {@code reportedAt} is the one kept, which is both the
     * earlier and the truer one - the same rule {@code resolve} follows for the resolution time.
     *
     * <p>Only meaningful while this row is OPEN - a resolved problem that happens again is a new
     * problem, and callers filter on status before asking.
     */
    public boolean restates(UUID otherTripStopId, TripExceptionType otherType, String otherNotes) {
        return status == TripExceptionStatus.OPEN
                && exceptionType == otherType
                && Objects.equals(tripStopId, otherTripStopId)
                && Objects.equals(notes, otherNotes);
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
