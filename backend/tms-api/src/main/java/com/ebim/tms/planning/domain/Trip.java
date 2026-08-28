package com.ebim.tms.planning.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One vehicle's planned journey inside a {@link PlanningRun} (migration V11).
 *
 * <p>Carries no origin of its own - a trip departs from its run's origin, so "trip and run agree
 * on company and origin" is structural rather than an invariant that could be violated.
 *
 * <p>Owns its {@link TripStop} list ({@link #syncStops}/{@link #reorderStops}) but <em>not</em>
 * its assignments: {@link TripOrderAssignment} is a separate aggregate reached through its
 * repository, so loading a trip never drags in the orders on it, and so an assignment's history
 * survives independently of the trip's own edits.
 *
 * <p>Capacity is deliberately <em>not</em> a field while the trip is {@link TripStatus#DRAFT}:
 * every check resolves the vehicle's current effective capacity live, so a fleet correction is
 * picked up immediately. {@link #confirm} freezes the three numbers into
 * {@code snapshotMax*}, after which a later fleet edit can no longer change what a confirmed plan
 * was validated against. See {@code docs/domain/CAPACITY_MODEL.md}.
 *
 * <p>A trip is also the internal name of what an external system calls a <em>Shipment</em>
 * ({@code docs/domain/SHIPMENT_V2.md}). {@link #shipmentNumber} is its identity out there;
 * {@link #tripNumber} stays its identity inside one planning run and means nothing without it.
 */
@Entity
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "planning_run_id", updatable = false, nullable = false)
    private UUID planningRunId;

    /**
     * Denormalized from {@code planningRunId}'s {@link PlanningRun#planningDate()} at
     * construction time (migration V16, rule 7) - never changes afterwards, since nothing on
     * {@link PlanningRun} mutates its own planning date. Exists so the database can enforce "one
     * active trip per vehicle per planning date" ({@code uq_trip_vehicle_active_planning_date})
     * without a join back to {@code planning_run}.
     */
    @Column(name = "planning_date", updatable = false, nullable = false)
    private LocalDate planningDate;

    @Column(name = "trip_number", updatable = false, nullable = false)
    private int tripNumber;

    /**
     * The stable, installation-wide identity of this trip as an external Shipment (migration
     * V19). Assigned once at construction from {@code tms.shipment_number_seq} and never
     * reissued - {@code updatable = false} so no code path can even try.
     */
    @Column(name = "shipment_number", updatable = false, nullable = false)
    private String shipmentNumber;

    /**
     * The master route this shipment was built from, or null - a suggestion, never a constraint.
     * See {@link #applyRoute} and {@code docs/domain/SHIPMENT_V2.md}, "Route master interaction".
     */
    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "carrier_id")
    private UUID carrierId;

    /**
     * The carrier that <em>accepted</em> a tender for this shipment, which is not necessarily the
     * owner of the vehicle on it (migration V42).
     *
     * <p>{@link #carrierId} goes on meaning what it has always meant: the owner of the assigned
     * vehicle, set by {@link #assignVehicle}. Subcontracting makes the two legitimately disagree
     * for a while - the carrier is agreed, the truck is not sorted out yet - and this field is what
     * makes that state expressible instead of silently lost. It is also what makes it
     * <em>blocking</em>: {@link #dispatch} refuses while the two disagree, and
     * {@code ck_trip_departed_carrier_matches_vehicle} refuses in the database.
     *
     * <p>Null on every shipment nobody has tendered elsewhere, which is almost all of them. Null
     * means "no acceptance says anything different from the vehicle", not "unknown".
     */
    @Column(name = "accepted_carrier_id")
    private UUID acceptedCarrierId;

    /**
     * The driver planned to run this shipment, or null when none has been named yet (migration
     * V26). Not required by any state - see {@link #assignDriver}.
     */
    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "planned_departure_at")
    private OffsetDateTime plannedDepartureAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TripStatus status;

    @Column(name = "snapshot_max_weight_kg", precision = 10, scale = 2)
    private BigDecimal snapshotMaxWeightKg;

    @Column(name = "snapshot_max_volume_m3", precision = 10, scale = 3)
    private BigDecimal snapshotMaxVolumeM3;

    @Column(name = "snapshot_max_pallets")
    private Integer snapshotMaxPallets;

    @Column(name = "capacity_snapshot_at")
    private OffsetDateTime capacitySnapshotAt;

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

    /**
     * The three execution facts (migration V25), each written once by its own transition and never
     * afterwards. All are <em>operator-supplied</em> business times, not the instant the request
     * arrived: a dispatcher recording an 08:40 departure at 09:05 must be able to say 08:40. When
     * the button was pressed, and by whom, is {@code tms.audit_event}'s job.
     */
    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

    @Column(name = "ready_by")
    private UUID readyBy;

    @Column(name = "actual_departure_at")
    private OffsetDateTime actualDepartureAt;

    @Column(name = "dispatched_by")
    private UUID dispatchedBy;

    @Column(name = "actual_completion_at")
    private OffsetDateTime actualCompletionAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TripStop> stops = new ArrayList<>();

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

    protected Trip() {
        // JPA
    }

    public Trip(UUID companyId, UUID planningRunId, LocalDate planningDate, int tripNumber, String shipmentNumber,
            UUID vehicleId, UUID carrierId, OffsetDateTime plannedDepartureAt, UUID actorId) {
        this.companyId = companyId;
        this.planningRunId = planningRunId;
        this.planningDate = planningDate;
        this.tripNumber = tripNumber;
        this.shipmentNumber = shipmentNumber;
        this.vehicleId = vehicleId;
        this.carrierId = carrierId;
        this.plannedDepartureAt = plannedDepartureAt;
        this.status = TripStatus.DRAFT;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID planningRunId() {
        return planningRunId;
    }

    public LocalDate planningDate() {
        return planningDate;
    }

    public int tripNumber() {
        return tripNumber;
    }

    public String shipmentNumber() {
        return shipmentNumber;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public UUID acceptedCarrierId() {
        return acceptedCarrierId;
    }

    /**
     * Whether this shipment is agreed with a carrier that does not own the vehicle on it.
     *
     * <p>True is an ordinary planning state and not an error: somebody accepted, and a compatible
     * vehicle has still to be put on. It is a state a shipment may sit in, be edited in and be
     * re-planned in - and may not depart in.
     */
    public boolean awaitsCarrierVehicle() {
        return acceptedCarrierId != null && !acceptedCarrierId.equals(carrierId);
    }

    public UUID carrierId() {
        return carrierId;
    }

    public UUID driverId() {
        return driverId;
    }

    public OffsetDateTime plannedDepartureAt() {
        return plannedDepartureAt;
    }

    public TripStatus status() {
        return status;
    }

    public boolean isDraft() {
        return status == TripStatus.DRAFT;
    }

    /**
     * Whether this trip reads frozen capacity rather than its vehicle's live capacity - true from
     * confirmation onwards, and still true for a trip cancelled after it was confirmed. Asked
     * instead of {@code status() == CONFIRMED} everywhere the question is really "was this plan
     * ever made binding?", which is what migration V25's {@code ck_trip_draft_has_no_snapshot}
     * enforces.
     */
    public boolean hasCapacitySnapshot() {
        return capacitySnapshotAt != null;
    }

    public BigDecimal snapshotMaxWeightKg() {
        return snapshotMaxWeightKg;
    }

    public BigDecimal snapshotMaxVolumeM3() {
        return snapshotMaxVolumeM3;
    }

    public Integer snapshotMaxPallets() {
        return snapshotMaxPallets;
    }

    public OffsetDateTime capacitySnapshotAt() {
        return capacitySnapshotAt;
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

    public String cancelReason() {
        return cancelReason;
    }

    public OffsetDateTime readyAt() {
        return readyAt;
    }

    public UUID readyBy() {
        return readyBy;
    }

    public OffsetDateTime actualDepartureAt() {
        return actualDepartureAt;
    }

    public UUID dispatchedBy() {
        return dispatchedBy;
    }

    public OffsetDateTime actualCompletionAt() {
        return actualCompletionAt;
    }

    public UUID completedBy() {
        return completedBy;
    }

    public long version() {
        return version;
    }

    /** Ordered by stop sequence, ascending (1..N - see {@link #syncStops}). */
    public List<TripStop> stops() {
        return stops.stream().sorted(Comparator.comparingInt(TripStop::sequence)).toList();
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

    /**
     * Sets or swaps the vehicle (and the carrier resolved from it) and the planned departure.
     * Whether the trip's current load still fits the new vehicle is
     * {@code TripService.updateVehicle}'s check, made <em>before</em> this is called - the entity
     * does not know what is assigned to it (assignments are a separate aggregate).
     */
    public void assignVehicle(UUID vehicleId, UUID carrierId, OffsetDateTime plannedDepartureAt, UUID actorId) {
        this.vehicleId = vehicleId;
        this.carrierId = carrierId;
        this.plannedDepartureAt = plannedDepartureAt;
        this.updatedBy = actorId;
    }

    /**
     * Records that a carrier accepted a tender for this shipment (migration V42).
     *
     * <p>Deliberately does <b>not</b> touch {@link #vehicleId} or {@link #carrierId}. Clearing the
     * vehicle is impossible - {@code ck_trip_confirmed_is_complete} requires one on every confirmed
     * trip, and only confirmed trips are tenderable - and choosing one of the accepting carrier's
     * automatically would mean picking among another company's fleet by rules nobody has stated.
     * So the acceptance is recorded as the fact it is, and the shipment is stopped from departing
     * until a planner resolves it. See {@link #awaitsCarrierVehicle}.
     */
    public void recordCarrierAcceptance(UUID acceptingCarrierId, UUID actorId) {
        this.acceptedCarrierId = acceptingCarrierId;
        // Null when a carrier answered over the integration API and there is no person to name.
        // Left alone rather than written, because overwriting the last human who touched this
        // shipment with "nobody" loses a fact to record the absence of one.
        if (actorId != null) {
            this.updatedBy = actorId;
        }
    }

    /**
     * Names the driver who will run this shipment, or clears the name when {@code driverId} is
     * null.
     *
     * <p>Whether that driver may be assigned - same company, active, licence still valid on the
     * planning date, carrier compatible with the vehicle's - is {@code TripService.updateDriver}'s
     * check, made before this is called. The entity does not know those things: two of them live
     * in the fleet master, which {@code planning} reaches only through
     * {@code DriverLookupPort}.
     *
     * <p>Unlike {@link #assignVehicle} this is permitted after confirmation, and that asymmetry is
     * the point. Swapping a vehicle changes what the plan was validated against - the capacity a
     * confirmed trip is frozen at ({@code docs/domain/CAPACITY_MODEL.md}) - while swapping a
     * driver changes nothing a shipment was proved against. A driver calling in sick at 05:00 on
     * a trip confirmed last night is the ordinary case, and forcing a dispatcher to cancel and
     * rebuild the shipment to answer it would make the lifecycle the enemy of the day.
     * {@code TripService} still refuses it once the vehicle has left: at that point who is driving
     * is a fact, not a plan.
     */
    public void assignDriver(UUID driverId, UUID actorId) {
        this.driverId = driverId;
        this.updatedBy = actorId;
    }

    /**
     * Freezes the capacity this trip was validated against and locks it. Legality (draft run,
     * vehicle present, load within capacity, stops complete) is
     * {@code PlanningRunService.confirm}'s concern.
     */
    public void confirm(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, Integer maxPallets, UUID actorId) {
        requireTransitionTo(TripStatus.CONFIRMED);
        this.status = TripStatus.CONFIRMED;
        this.snapshotMaxWeightKg = maxWeightKg;
        this.snapshotMaxVolumeM3 = maxVolumeM3;
        this.snapshotMaxPallets = maxPallets;
        this.capacitySnapshotAt = OffsetDateTime.now();
        this.confirmedAt = OffsetDateTime.now();
        this.confirmedBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Declares the shipment loaded, documented and waiting for its driver.
     *
     * <p>{@code readyAt} is the operator's own time, which is why it is a parameter and not
     * {@code now()}: the same reason {@link #dispatch} and {@link #complete} take theirs. Whether
     * it is a sane time (not in the future, not before confirmation) is
     * {@code TripExecutionService}'s check, made with a message a dispatcher can read; migration
     * V25's {@code ck_trip_execution_times_ordered} is the backstop.
     */
    public void markReadyForDispatch(OffsetDateTime readyAt, UUID actorId) {
        requireTransitionTo(TripStatus.READY_FOR_DISPATCH);
        this.status = TripStatus.READY_FOR_DISPATCH;
        this.readyAt = readyAt;
        this.readyBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Sends the vehicle out. {@code actualDepartureAt} is recorded <em>beside</em>
     * {@link #plannedDepartureAt} and never over it: the gap between the two is the delay, and a
     * dispatch that overwrote the plan would erase the only evidence there was one.
     */
    public void dispatch(OffsetDateTime actualDepartureAt, UUID actorId) {
        requireTransitionTo(TripStatus.IN_TRANSIT);
        if (awaitsCarrierVehicle()) {
            throw new IllegalStateException("trip " + shipmentNumber + " was accepted by a carrier that does not"
                    + " own the vehicle assigned to it, and cannot depart until one of that carrier's vehicles"
                    + " is assigned");
        }
        this.status = TripStatus.IN_TRANSIT;
        this.actualDepartureAt = actualDepartureAt;
        this.dispatchedBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Closes the trip out. Terminal: nothing follows {@link TripStatus#COMPLETED}.
     *
     * <p>Every stop must have been resolved first - completed, skipped or failed (migration V27).
     * A trip closed over three stops nobody ever touched is a day that looks finished and is not,
     * and the whole reason per-stop execution exists is to stop that being recordable.
     * {@code TripExecutionService} refuses first, naming the stops; this is the last line of
     * defense, in the transaction that broke it.
     */
    public void complete(OffsetDateTime actualCompletionAt, UUID actorId) {
        requireTransitionTo(TripStatus.COMPLETED);
        if (hasUnresolvedStops()) {
            throw new IllegalStateException(
                    "trip " + shipmentNumber + " still has stops that have not been resolved");
        }
        this.status = TripStatus.COMPLETED;
        this.actualCompletionAt = actualCompletionAt;
        this.completedBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Legality beyond the transition table is {@code TripService.cancel}'s concern; releasing the
     * orders is its job too.
     *
     * <p>Reachable from {@link TripStatus#DRAFT}, {@link TripStatus#CONFIRMED} and
     * {@link TripStatus#READY_FOR_DISPATCH} - never from {@link TripStatus#IN_TRANSIT}, where
     * "this trip did not happen" has stopped being true. Everything already recorded is kept:
     * a trip cancelled after being made ready keeps its {@code confirmedAt} and {@code readyAt},
     * because those things did happen.
     */
    public void cancel(String reason, UUID actorId) {
        requireTransitionTo(TripStatus.CANCELLED);
        this.status = TripStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        this.cancelledBy = actorId;
        this.cancelReason = reason;
        this.updatedBy = actorId;
    }

    /**
     * Records what happened at one of this trip's stops (migration V27).
     *
     * <p>Goes through the aggregate root rather than letting a service reach into
     * {@link TripStop} directly, which is why {@code TripStop.recordOutcome} is package-private:
     * the two rules that make a stop transition legal are <em>the trip's</em>, not the stop's -
     * the stop must belong to this trip, and the trip must be on the road. A service holding a
     * {@code TripStop} it loaded by id could satisfy neither.
     *
     * @param stopId the stop to record against; must belong to this trip
     * @return the stop that was updated, so a caller can build its view without re-finding it
     * @throws IllegalArgumentException if the stop is not one of this trip's
     * @throws IllegalStateException if the trip is not {@link TripStatus#IN_TRANSIT}, or if the
     *     outcome does not follow the stop's current one
     */
    public TripStop recordStopOutcome(UUID stopId, StopExecutionStatus outcome, OffsetDateTime occurredAt,
            String notes, UUID actorId) {
        if (status != TripStatus.IN_TRANSIT) {
            throw new IllegalStateException(
                    "trip " + shipmentNumber + " is " + status + " and its stops cannot be worked");
        }
        // stopId.equals(candidate.id()) and not the other way round: a stop that has not been
        // flushed yet has a null id, and the natural phrasing would answer that with a
        // NullPointerException instead of "not one of this trip's".
        TripStop stop = stops.stream()
                .filter(candidate -> stopId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "stop " + stopId + " does not belong to trip " + shipmentNumber));
        stop.recordOutcome(outcome, occurredAt, notes, actorId);
        this.updatedBy = actorId;
        return stop;
    }

    /**
     * Whether any stop still needs somebody to do something about it - the question
     * {@link #complete} asks, and the one the workspace turns into "3 of 7 stops done".
     */
    public boolean hasUnresolvedStops() {
        return stops.stream().anyMatch(stop -> stop.executionStatus().isOutstanding());
    }

    /** The outstanding stops in visiting order, for a refusal that can name them. */
    public List<TripStop> unresolvedStops() {
        return stops().stream().filter(stop -> stop.executionStatus().isOutstanding()).toList();
    }

    /**
     * The transition table's last line of defense, in the transaction that broke it.
     *
     * <p>An {@link IllegalStateException} and not a caller-facing 4xx on purpose: every service
     * path consults {@link TripStatus#canTransitionTo} first and refuses with a message naming the
     * two states, so reaching this is a defect in a caller that skipped that check - and the
     * honest answer to a defect is a rolled-back transaction, not a shipment recorded as departed
     * from a state it could not have departed from. Same reasoning as
     * {@link #assertStopSequenceIntegrity}.
     */
    private void requireTransitionTo(TripStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "trip " + shipmentNumber + " cannot move from " + status + " to " + target);
        }
    }

    /**
     * Brings the stop list in line with what is currently assigned, <em>preserving the planner's
     * ordering</em>: a destination that is still served keeps its relative position and gets its
     * window envelope refreshed, a destination whose last order left is removed, and a newly
     * appearing destination is appended at the end. Sequences are then renumbered 1..N with no
     * gaps.
     *
     * <p>Rebuilding the whole list from scratch on every assignment change would be simpler and
     * would silently discard the manual ordering a planner just set - the one thing manual
     * planning exists to let them do.
     */
    public void syncStops(List<StopPlan> plans, UUID actorId) {
        Map<UUID, StopPlan> planned = new LinkedHashMap<>();
        plans.forEach(plan -> planned.put(plan.destinationId(), plan));

        stops.removeIf(stop -> !planned.containsKey(stop.destinationId()));

        Map<UUID, TripStop> existing = stops.stream()
                .collect(Collectors.toMap(TripStop::destinationId, stop -> stop));
        for (StopPlan plan : planned.values()) {
            TripStop stop = existing.get(plan.destinationId());
            if (stop == null) {
                stops.add(new TripStop(this, plan.destinationId(), stops.size() + 1, plan.serviceWindowStart(),
                        plan.serviceWindowEnd(), actorId));
            } else {
                stop.applyWindow(plan.serviceWindowStart(), plan.serviceWindowEnd(), actorId);
            }
        }

        renumber(stops.stream().sorted(Comparator.comparingInt(TripStop::sequence)).toList(), actorId);
        this.updatedBy = actorId;
    }

    /**
     * Applies an explicit planner ordering. {@code destinationIdsInOrder} must be exactly the set
     * of destinations currently stopped at - {@code TripService.reorderStops} validates that with
     * a caller-facing message first; the check here is the invariant's last line of defense, not
     * the user-facing one.
     */
    public void reorderStops(List<UUID> destinationIdsInOrder, UUID actorId) {
        Set<UUID> current = stops.stream().map(TripStop::destinationId).collect(Collectors.toSet());
        if (destinationIdsInOrder.size() != current.size() || !current.containsAll(destinationIdsInOrder)) {
            throw new IllegalArgumentException("the reordered stop list must contain exactly the trip's current stops");
        }

        Map<UUID, TripStop> byDestination = stops.stream()
                .collect(Collectors.toMap(TripStop::destinationId, stop -> stop));
        renumber(destinationIdsInOrder.stream().map(byDestination::get).toList(), actorId);
        this.updatedBy = actorId;
    }

    /**
     * Points this shipment at a master route, or clears the pointer when {@code routeId} is null.
     *
     * <p>{@code routeDestinationOrder} is the master's own stop order and is used only to
     * <em>reorder what the shipment already has</em>: destinations the route names keep the
     * route's relative order and move to the front, and every other stop keeps its relative order
     * behind them. Nothing is created and nothing is removed - a stop exists because an order is
     * going there ({@link #syncStops}), never because a corridor mentions it, and a route that
     * omits a served destination must not be able to drop it from the plan. Pass an empty list to
     * record the route without touching the sequence.
     *
     * <p>Legality (route is active, in this company, and departs from the run's origin) is
     * {@code TripService.updateRoute}'s concern.
     */
    public void applyRoute(UUID routeId, List<UUID> routeDestinationOrder, UUID actorId) {
        this.routeId = routeId;
        if (!routeDestinationOrder.isEmpty()) {
            Map<UUID, TripStop> byDestination = stops.stream()
                    .collect(Collectors.toMap(TripStop::destinationId, stop -> stop));
            List<TripStop> ordered = new ArrayList<>();
            routeDestinationOrder.stream().distinct()
                    .map(byDestination::get)
                    .filter(Objects::nonNull)
                    .forEach(ordered::add);
            stops.stream().sorted(Comparator.comparingInt(TripStop::sequence))
                    .filter(stop -> !ordered.contains(stop))
                    .forEach(ordered::add);
            renumber(ordered, actorId);
        }
        this.updatedBy = actorId;
    }

    /**
     * The stop-sequence invariant, asserted rather than assumed: positions are exactly 1..N, each
     * used once, and one destination appears once.
     *
     * <p>{@link #renumber} is the only writer of a sequence and cannot produce anything else, so
     * a failure here means the list was mutated by a path that bypassed it - which is precisely
     * what this catches, in the transaction that did it, instead of leaving a shipment whose stop
     * 3 is missing for a driver to discover. Deliberately Java and not a database trigger: V11's
     * header rules out triggers carrying planning logic, and {@code uq_trip_stop_trip_sequence}
     * already covers the declarative half (uniqueness), leaving only contiguity to check here.
     *
     * @throws IllegalStateException if the list is not a contiguous 1..N sequence
     */
    public void assertStopSequenceIntegrity() {
        List<Integer> sequences = stops.stream().map(TripStop::sequence).sorted().toList();
        for (int index = 0; index < sequences.size(); index++) {
            if (sequences.get(index) != index + 1) {
                throw new IllegalStateException("trip " + shipmentNumber + " has a broken stop sequence "
                        + sequences + " for " + sequences.size() + " stops");
            }
        }
        long distinctDestinations = stops.stream().map(TripStop::destinationId).distinct().count();
        if (distinctDestinations != stops.size()) {
            throw new IllegalStateException(
                    "trip " + shipmentNumber + " stops at the same destination more than once");
        }
    }

    private void renumber(List<TripStop> ordered, UUID actorId) {
        int sequence = 1;
        for (TripStop stop : ordered) {
            stop.applySequence(sequence++, actorId);
        }
        assertStopSequenceIntegrity();
    }

    /**
     * Bumps {@code version} when something that belongs to the trip's <em>aggregate boundary but
     * not its row</em> changes - an assignment added or removed. Without this a planner holding a
     * stale trip board would never be told their view of the load is out of date, because the
     * trip row itself was untouched. See {@code docs/domain/PLANNING_MANUAL_V1.md},
     * "Concurrency".
     */
    public void touch(UUID actorId) {
        this.updatedBy = actorId;
    }
}
