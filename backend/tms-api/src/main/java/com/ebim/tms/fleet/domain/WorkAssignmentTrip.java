package com.ebim.tms.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One shipment's place in a day's work (migration V47).
 *
 * <p>The window and the reposition are <b>frozen when the sequence was validated</b>, not derived on
 * read - the same argument V30 makes for cost lines and V43 for the stop ETA. A shipment whose route
 * changed afterwards shows a stale window, which is visibly stale, rather than silently re-deciding
 * that a day somebody committed to is still feasible.
 */
@Entity
@Table(name = "work_assignment_trip")
public class WorkAssignmentTrip {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_assignment_id", nullable = false)
    private WorkAssignment assignment;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "planned_start")
    private OffsetDateTime plannedStart;

    @Column(name = "planned_end")
    private OffsetDateTime plannedEnd;

    /** NULL for the first shipment, and NULL when routing could not measure it - never zero. */
    @Column(name = "reposition_minutes")
    private Integer repositionMinutes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected WorkAssignmentTrip() {
    }

    public WorkAssignmentTrip(UUID companyId, UUID tripId, OffsetDateTime plannedStart,
            OffsetDateTime plannedEnd, Integer repositionMinutes) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.plannedStart = plannedStart;
        this.plannedEnd = plannedEnd;
        this.repositionMinutes = repositionMinutes;
    }

    void attachTo(WorkAssignment assignment, int sequence) {
        this.assignment = assignment;
        this.sequence = sequence;
    }

    public UUID id() {
        return id;
    }

    public UUID tripId() {
        return tripId;
    }

    public int sequence() {
        return sequence;
    }

    public OffsetDateTime plannedStart() {
        return plannedStart;
    }

    public OffsetDateTime plannedEnd() {
        return plannedEnd;
    }

    public Integer repositionMinutes() {
        return repositionMinutes;
    }
}
