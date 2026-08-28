package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 *
 * <p><b>Two halves.</b> Everything above {@code executionStatus} is the <em>plan</em> - where to
 * go, in what order, inside which window - and is written only while the trip is a draft.
 * Everything from {@code executionStatus} down is what actually happened there (migration V27),
 * written only once the trip is on the road. The two never overwrite each other: the planned
 * window stays exactly as planned however late the vehicle arrived, because the gap between the
 * two is the number a report wants.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false)
    private StopExecutionStatus executionStatus = StopExecutionStatus.PENDING;

    @Column(name = "actual_arrival_at")
    private OffsetDateTime actualArrivalAt;

    @Column(name = "service_started_at")
    private OffsetDateTime serviceStartedAt;

    /** When the vehicle left this stop - the far end of the dwell time, not a second "completed at". */
    @Column(name = "actual_departure_at")
    private OffsetDateTime actualDepartureAt;

    /**
     * Free text from whoever worked the stop. Never the reason a stop was skipped or failed - that
     * is typed and lives in {@link TripException}, so it can be counted and reported on.
     */
    @Column(name = "execution_notes")
    private String executionNotes;

    /**
     * When the vehicle is expected here (migration V43, ADR-011).
     *
     * <p><b>Null means there is no estimate</b>, not that it is zero or unknown-but-soon. A leg on
     * the way could not be measured, so this stop and every stop after it have none - see
     * {@link StopScheduleEngine}. The five ETA fields are written together and cleared together
     * ({@code ck_trip_stop_eta_complete}), so a reader never sees half an estimate.
     *
     * <p>Stamped rather than derived on read, the way V30 stored cost lines: a time a person saw
     * and acted on has to stay reproducible after the master data behind it changed.
     */
    @Column(name = "eta_arrival_at")
    private OffsetDateTime etaArrivalAt;

    @Column(name = "eta_departure_at")
    private OffsetDateTime etaDepartureAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_source")
    private EtaSource etaSource;

    @Column(name = "eta_calculated_at")
    private OffsetDateTime etaCalculatedAt;

    /**
     * Whether the schedule has the vehicle arriving after this stop's window closes.
     *
     * <p>Stored rather than derived, because it is a property of the calculation and not of the
     * row: recomputing it later against a window somebody has since widened would quietly erase
     * the warning a planner acted on.
     */
    @Column(name = "eta_misses_window", nullable = false)
    private boolean etaMissesWindow;

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

    /**
     * The trip this stop belongs to, for the two control tower reads that hold stops without
     * holding their trips.
     *
     * <p>The parent itself stays unexposed: a stop is reached <em>through</em> its trip everywhere
     * else in the module, and handing callers the aggregate root from inside it would make the
     * boundary meaningless. An id is not a way back in - it is what a caller batches its own
     * company-scoped lookup with.
     *
     * <p>{@code trip} is a lazy association, so this initializes the proxy unless the trip is
     * already in the persistence context or was fetched with the stop. Both queries that call it
     * ({@code TripStopRepository.findByTripIds} and {@code findOutstandingForDay}) fetch it - the
     * whole point of the method is to keep a board of stops from becoming one select per row.
     */
    public UUID tripId() {
        return trip.id();
    }

    public UUID companyId() {
        return companyId;
    }

    /**
     * Writes a computed schedule onto this stop, or clears it when the schedule has none.
     *
     * <p>All five fields move together. A stop that could not be scheduled is cleared rather than
     * left holding a previous run's numbers - a stale arrival time beside a route that has changed
     * is worse than a blank, because it looks current.
     */
    public void applySchedule(StopSchedule schedule, OffsetDateTime calculatedAt) {
        if (schedule.isScheduled()) {
            this.etaArrivalAt = schedule.arrivalAt();
            this.etaDepartureAt = schedule.departureAt();
            this.etaSource = schedule.source();
            this.etaCalculatedAt = calculatedAt;
            this.etaMissesWindow = schedule.missesWindow();
        } else {
            clearSchedule();
        }
    }

    public void clearSchedule() {
        this.etaArrivalAt = null;
        this.etaDepartureAt = null;
        this.etaSource = null;
        this.etaCalculatedAt = null;
        this.etaMissesWindow = false;
    }

    public OffsetDateTime etaArrivalAt() {
        return etaArrivalAt;
    }

    public OffsetDateTime etaDepartureAt() {
        return etaDepartureAt;
    }

    public EtaSource etaSource() {
        return etaSource;
    }

    public OffsetDateTime etaCalculatedAt() {
        return etaCalculatedAt;
    }

    public boolean etaMissesWindow() {
        return etaMissesWindow;
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

    public StopExecutionStatus executionStatus() {
        return executionStatus;
    }

    public OffsetDateTime actualArrivalAt() {
        return actualArrivalAt;
    }

    public OffsetDateTime serviceStartedAt() {
        return serviceStartedAt;
    }

    public OffsetDateTime actualDepartureAt() {
        return actualDepartureAt;
    }

    public String executionNotes() {
        return executionNotes;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    /**
     * Records what happened at this stop, moving it to {@code outcome} and writing the one
     * timestamp that outcome is about.
     *
     * <p>One method rather than five, because the five differ only in which column the operator's
     * time lands in: the transition check, the actor and the notes are identical, and splitting
     * them would be four more places for those three to drift. Which outcome may follow which is
     * {@link StopExecutionStatus}'s answer, asserted here as the last line of defense -
     * {@code TripStopExecutionService} refuses first, with a sentence a dispatcher can read.
     *
     * <p>{@code notes} <em>replaces</em> what is on the row, and loses nothing by doing so: every
     * note is also written to that transition's {@link TransportEvent}, which is append-only, so
     * the column here is a convenience - "the sentence that explains this stop's current state",
     * readable without opening the timeline - rather than a second, growing copy of it. Appending
     * instead would eventually collide with the column's 500-character ceiling and force a
     * truncation rule nobody asked for.
     *
     * @param occurredAt the operator's own time for the fact, already validated by the service
     * @param notes optional free text, null to leave what is there
     */
    void recordOutcome(StopExecutionStatus outcome, OffsetDateTime occurredAt, String notes, UUID actorId) {
        if (!executionStatus.canTransitionTo(outcome)) {
            throw new IllegalStateException(
                    "stop " + sequence + " cannot move from " + executionStatus + " to " + outcome);
        }
        switch (outcome) {
            case ARRIVED -> this.actualArrivalAt = occurredAt;
            case IN_SERVICE -> this.serviceStartedAt = occurredAt;
            case COMPLETED -> this.actualDepartureAt = occurredAt;
            // SKIPPED and FAILED write no timestamp at all: a stop that was never attempted has
            // no arrival, and one that was attempted and refused has whatever arrival it already
            // recorded. Migration V27's ck_trip_stop_skipped_has_no_times is the backstop.
            case SKIPPED, FAILED -> { }
            case PENDING -> throw new IllegalStateException("a stop cannot be moved back to PENDING");
        }
        this.executionStatus = outcome;
        if (notes != null && !notes.isBlank()) {
            this.executionNotes = notes;
        }
        this.updatedBy = actorId;
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
