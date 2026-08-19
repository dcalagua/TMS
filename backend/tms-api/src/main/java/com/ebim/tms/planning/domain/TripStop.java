package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One ordered destination stop of a {@link Trip} (migration V11) - a <em>planning instance</em>
 * stop, not a master {@code RouteStop}. The two are deliberately unrelated: a route stop belongs
 * to a reusable corridor and carries no date, while this row belongs to one dated trip and
 * carries the service window that this day's assigned orders actually asked for.
 *
 * <p>{@code companyId} is denormalized from the parent trip so the destination reference can
 * carry the composite-FK tenant guarantee ({@code DATA_MODEL.md} rules 6-7), the same shape
 * {@code RouteStop} uses.
 *
 * <p>Never created directly by a caller: {@link Trip#syncStops} maintains the list from the
 * trip's active assignments, and {@link Trip#reorderStops} applies an explicit planner ordering.
 */
@Entity
@Table(name = "trip_stop")
public class TripStop {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, updatable = false)
    private Trip trip;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "destination_id", updatable = false, nullable = false)
    private UUID destinationId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "service_window_start")
    private LocalTime serviceWindowStart;

    @Column(name = "service_window_end")
    private LocalTime serviceWindowEnd;

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

    protected TripStop() {
        // JPA
    }

    TripStop(Trip trip, UUID destinationId, int sequence, LocalTime serviceWindowStart, LocalTime serviceWindowEnd,
            UUID actorId) {
        this.trip = trip;
        this.companyId = trip.companyId();
        this.destinationId = destinationId;
        this.sequence = sequence;
        this.serviceWindowStart = serviceWindowStart;
        this.serviceWindowEnd = serviceWindowEnd;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID destinationId() {
        return destinationId;
    }

    public int sequence() {
        return sequence;
    }

    public LocalTime serviceWindowStart() {
        return serviceWindowStart;
    }

    public LocalTime serviceWindowEnd() {
        return serviceWindowEnd;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    void applySequence(int sequence, UUID actorId) {
        this.sequence = sequence;
        this.updatedBy = actorId;
    }

    void applyWindow(LocalTime start, LocalTime end, UUID actorId) {
        this.serviceWindowStart = start;
        this.serviceWindowEnd = end;
        this.updatedBy = actorId;
    }
}
