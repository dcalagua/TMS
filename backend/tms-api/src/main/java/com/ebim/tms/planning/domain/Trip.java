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

    public UUID carrierId() {
        return carrierId;
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
     * Freezes the capacity this trip was validated against and locks it. Legality (draft run,
     * vehicle present, load within capacity, stops complete) is
     * {@code PlanningRunService.confirm}'s concern.
     */
    public void confirm(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, Integer maxPallets, UUID actorId) {
        this.status = TripStatus.CONFIRMED;
        this.snapshotMaxWeightKg = maxWeightKg;
        this.snapshotMaxVolumeM3 = maxVolumeM3;
        this.snapshotMaxPallets = maxPallets;
        this.capacitySnapshotAt = OffsetDateTime.now();
        this.confirmedAt = OffsetDateTime.now();
        this.confirmedBy = actorId;
        this.updatedBy = actorId;
    }

    /** Legality is {@code TripService.cancel}'s concern; releasing the orders is its job too. */
    public void cancel(String reason, UUID actorId) {
        this.status = TripStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        this.cancelledBy = actorId;
        this.cancelReason = reason;
        this.updatedBy = actorId;
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
