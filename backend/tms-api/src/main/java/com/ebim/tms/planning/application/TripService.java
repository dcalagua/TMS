package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trip use cases: create a trip in a draft run, set its vehicle, put orders on it, take them off,
 * move them between trips, order its stops and cancel it. Takes a {@link CompanyScope}, never a
 * company id, like every other service in TMS.
 *
 * <p><b>Concurrency.</b> Every mutation starts by taking the trip's row lock
 * ({@code SELECT ... FOR UPDATE} through {@link TripRepository#findByIdAndCompanyIdForUpdate}),
 * so "read the current load, decide it fits, write the assignment" cannot interleave with another
 * planner doing the same on the same trip. Two planners racing on <em>different</em> trips for the
 * same order are stopped by the database instead, through the partial unique index on the
 * assignment table - a row lock on trip A can say nothing about trip B. A move takes both locks,
 * ordered by trip id, so two opposite moves cannot deadlock. See
 * {@code docs/domain/PLANNING_MANUAL_V1.md}, "Concurrency", and the integration tests.
 *
 * <p><b>Capacity.</b> Never trusted from the client and never partially applied: the check runs
 * inside the same transaction as the write it guards, so a refusal leaves the trip exactly as it
 * was - including the "move A to B" case, where B's refusal must not have already emptied A.
 */
@Service
public class TripService {

    private final TripRepository tripRepository;
    private final PlanningRunRepository planningRunRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final TripAssignmentService assignments;
    private final OrderPlanningPort orderPlanningPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final PlanningCapacityService capacityService;
    private final TripViewAssembler assembler;
    private final AuditActorProvider auditActorProvider;

    public TripService(TripRepository tripRepository, PlanningRunRepository planningRunRepository,
            TripOrderAssignmentRepository assignmentRepository, TripAssignmentService assignments,
            OrderPlanningPort orderPlanningPort, VehicleLookupPort vehicleLookupPort,
            PlanningCapacityService capacityService, TripViewAssembler assembler,
            AuditActorProvider auditActorProvider) {
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignments = assignments;
        this.orderPlanningPort = orderPlanningPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.capacityService = capacityService;
        this.assembler = assembler;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public TripDetailView get(CompanyScope scope, UUID tripId) {
        return assembler.toDetail(find(scope, tripId), scope.companyId());
    }

    @Transactional(readOnly = true)
    public TripCapacityView capacity(CompanyScope scope, UUID tripId) {
        return assembler.capacityOf(find(scope, tripId), scope.companyId());
    }

    @Transactional
    public TripDetailView create(CompanyScope scope, UUID runId, TripCreateRequest request) {
        PlanningRun run = requireDraftRun(scope, runId);
        requireCurrentVersion("planning run", run.version(), request.version());

        UUID actorId = auditActorProvider.requireAppUserId();
        UUID carrierId = null;
        if (request.vehicleId() != null) {
            carrierId = requireAssignableVehicle(scope, request.vehicleId()).carrierId();
        }
        Trip trip = new Trip(scope.companyId(), run.id(), tripRepository.maxTripNumber(run.id()) + 1,
                request.vehicleId(), carrierId, request.plannedDepartureAt(), actorId);
        return assembler.toDetail(save(trip), scope.companyId());
    }

    /**
     * Sets or swaps the vehicle. Everything already on the trip is revalidated against the new
     * vehicle's capacity <em>before</em> the swap is applied, so downgrading to a smaller truck
     * with a full load is refused rather than silently producing an over-capacity plan.
     */
    @Transactional
    public TripDetailView updateVehicle(CompanyScope scope, UUID tripId, TripVehicleRequest request) {
        Trip trip = lockedDraftTrip(scope, tripId);
        requireCurrentVersion("trip", trip.version(), request.version());

        VehicleCapacityReference vehicle = requireAssignableVehicle(scope, request.vehicleId());
        CapacityLoad load = assignments.currentLoad(trip.id());
        capacityService.requireWithinCapacity(
                "Vehicle " + vehicle.code() + " cannot take what is already planned on trip " + trip.tripNumber(),
                CapacityLimits.of(vehicle), load);

        trip.assignVehicle(vehicle.id(), vehicle.carrierId(), request.plannedDepartureAt(),
                auditActorProvider.requireAppUserId());
        return assembler.toDetail(save(trip), scope.companyId());
    }

    @Transactional
    public TripDetailView assignOrder(CompanyScope scope, UUID tripId, AssignOrderRequest request) {
        Trip trip = lockedDraftTripForAssignment(scope, tripId);
        PlanningRun run = requireDraftRun(scope, trip.planningRunId());
        PlannableOrder order = orderPlanningPort.findAssignable(request.orderId(), scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "orderId does not reference an order that is ready for planning in this company."));
        requireOrderFitsRun(order, run);
        requireNotAlreadyAssigned(order);

        CapacityLoad load = assignments.currentLoad(trip.id()).plus(CapacityLoad.of(order));
        capacityService.requireWithinCapacity(
                "Order " + order.orderNumber() + " does not fit trip " + trip.tripNumber(),
                liveLimits(trip, scope.companyId()), load);

        UUID actorId = auditActorProvider.requireAppUserId();
        assignments.open(trip, order, actorId);
        orderPlanningPort.markPlanned(order.id(), scope.companyId());
        assignments.refreshStops(trip, scope.companyId(), actorId);
        trip.touch(actorId);
        return assembler.toDetail(save(trip), scope.companyId());
    }

    @Transactional
    public TripDetailView removeOrder(CompanyScope scope, UUID tripId, UUID orderId, String reason) {
        Trip trip = lockedDraftTripForAssignment(scope, tripId);
        TripOrderAssignment assignment = requireActiveAssignment(trip, orderId);

        UUID actorId = auditActorProvider.requireAppUserId();
        assignments.closeAndRelease(assignment, blankToNull(reason), actorId);
        assignments.refreshStops(trip, scope.companyId(), actorId);
        trip.touch(actorId);
        return assembler.toDetail(save(trip), scope.companyId());
    }

    /**
     * Moves an order between two trips atomically. The order never leaves {@code PLANNED} - it
     * changes trips, it does not return to the pool - and if the target has no room the whole
     * transaction rolls back with the source assignment untouched.
     *
     * <p>Returns the <em>source</em> trip's detail, because that is the trip the caller was
     * looking at; the target is refreshed by the board's own reload.
     */
    @Transactional
    public TripDetailView moveOrder(CompanyScope scope, UUID tripId, UUID orderId, MoveOrderRequest request) {
        if (tripId.equals(request.targetTripId())) {
            throw new InvalidRequestException("targetTripId must be a different trip than the current one.");
        }

        // Locked in id order, always: two planners moving orders in opposite directions between
        // the same two trips would otherwise each hold the lock the other needs.
        Stream.of(tripId, request.targetTripId()).sorted().forEach(id -> lockedDraftTripForAssignment(scope, id));
        Trip source = find(scope, tripId);
        Trip target = find(scope, request.targetTripId());

        TripOrderAssignment assignment = requireActiveAssignment(source, orderId);
        PlanningRun targetRun = requireDraftRun(scope, target.planningRunId());
        PlannableOrder order = orderPlanningPort.findAllInCompany(Set.of(orderId), scope.companyId()).get(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found.");
        }
        requireOrderFitsRun(order, targetRun);

        CapacityLoad targetLoad = assignments.currentLoad(target.id()).plus(CapacityLoad.of(order));
        capacityService.requireWithinCapacity(
                "Order " + order.orderNumber() + " does not fit trip " + target.tripNumber(),
                liveLimits(target, scope.companyId()), targetLoad);

        UUID actorId = auditActorProvider.requireAppUserId();
        // Close first, then open: the partial unique index is checked per statement, and closing
        // is what frees the order for the new row - see TripAssignmentService.close.
        assignments.close(assignment, "Moved to trip " + target.tripNumber(), actorId);
        assignments.open(target, order, actorId);
        assignments.refreshStops(source, scope.companyId(), actorId);
        assignments.refreshStops(target, scope.companyId(), actorId);
        source.touch(actorId);
        target.touch(actorId);
        save(target);
        return assembler.toDetail(save(source), scope.companyId());
    }

    /**
     * Applies an explicit stop order. The submitted destinations must be exactly the ones the trip
     * currently serves: stops exist to sequence what is assigned, so this endpoint reorders, it
     * never creates or deletes a stop (assignments do that, through
     * {@code TripAssignmentService.refreshStops}).
     */
    @Transactional
    public TripDetailView reorderStops(CompanyScope scope, UUID tripId, TripStopOrderRequest request) {
        Trip trip = lockedDraftTripForAssignment(scope, tripId);

        List<UUID> submitted = request.destinationIds();
        Set<UUID> current = trip.stops().stream().map(TripStop::destinationId).collect(Collectors.toSet());
        if (submitted.size() != new HashSet<>(submitted).size()) {
            throw new InvalidRequestException("destinationIds must not repeat a destination.");
        }
        if (submitted.size() != current.size() || !current.containsAll(submitted)) {
            throw new InvalidRequestException(
                    "destinationIds must contain exactly the destinations this trip currently stops at ("
                            + current.size() + ").");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        trip.reorderStops(submitted, actorId);
        return assembler.toDetail(save(trip), scope.companyId());
    }

    /** Cancels a draft trip and returns every order on it to the eligible pool. */
    @Transactional
    public TripDetailView cancel(CompanyScope scope, UUID tripId, PlanningActionRequest request) {
        Trip trip = lockedDraftTrip(scope, tripId);
        requireCurrentVersion("trip", trip.version(), request.version());

        UUID actorId = auditActorProvider.requireAppUserId();
        String reason = blankToNull(request.reason());
        assignments.releaseAll(trip, reason == null ? "Trip cancelled" : reason, actorId);
        trip.cancel(reason, actorId);
        return assembler.toDetail(save(trip), scope.companyId());
    }

    private Trip find(CompanyScope scope, UUID tripId) {
        return tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    /** Locks a draft trip for an edit whose version the caller has already presented. */
    private Trip lockedDraftTrip(CompanyScope scope, UUID tripId) {
        Trip trip = tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        requireDraft(trip);
        return trip;
    }

    /**
     * Locks a draft trip <em>and</em> bumps its version, for the operations that change its load
     * or its stops without touching a column of its own - see
     * {@link TripRepository#findByIdAndCompanyIdForAssignment}.
     */
    private Trip lockedDraftTripForAssignment(CompanyScope scope, UUID tripId) {
        Trip trip = tripRepository.findByIdAndCompanyIdForAssignment(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        requireDraft(trip);
        return trip;
    }

    private static void requireDraft(Trip trip) {
        if (!trip.isDraft()) {
            throw new ConflictException("Trip " + trip.tripNumber() + " is " + trip.status()
                    + " and can no longer be modified.");
        }
    }

    private PlanningRun requireDraftRun(CompanyScope scope, UUID runId) {
        PlanningRun run = planningRunRepository.findByIdAndCompanyId(runId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Planning run not found."));
        if (!run.isDraft()) {
            throw new ConflictException("Planning run " + run.planNumber() + " is " + run.status()
                    + " and can no longer be modified.");
        }
        return run;
    }

    /**
     * The tenant and scope rules of an assignment, all three of them refused with 400 and the same
     * shape of message: an order may only be planned in its own company (already guaranteed by the
     * company-scoped lookup above), from the origin the run departs from, for the date the run
     * plans. The composite foreign keys on {@code trip_order_assignment} are the database's own
     * copy of the first rule.
     */
    private static void requireOrderFitsRun(PlannableOrder order, PlanningRun run) {
        if (!order.originId().equals(run.originId())) {
            throw new InvalidRequestException("Order " + order.orderNumber()
                    + " departs from a different origin than planning run " + run.planNumber() + ".");
        }
        if (!order.serviceDate().equals(run.planningDate())) {
            throw new InvalidRequestException("Order " + order.orderNumber() + " has service date "
                    + order.serviceDate() + ", which is not the planning date of run " + run.planNumber() + " ("
                    + run.planningDate() + ").");
        }
    }

    private void requireNotAlreadyAssigned(PlannableOrder order) {
        assignmentRepository.findByOrderIdAndStatusAndWholeOrderTrue(order.id(), AssignmentStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new ConflictException("Order " + order.orderNumber()
                            + " is already assigned to a trip. Move it instead of assigning it again.");
                });
    }

    private TripOrderAssignment requireActiveAssignment(Trip trip, UUID orderId) {
        return assignmentRepository.findByTripIdAndOrderIdAndStatus(trip.id(), orderId, AssignmentStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("This order is not currently assigned to this trip."));
    }

    private VehicleCapacityReference requireAssignableVehicle(CompanyScope scope, UUID vehicleId) {
        return vehicleLookupPort.findAssignable(vehicleId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "vehicleId does not reference an active, available vehicle in this company."));
    }

    /**
     * The limits a <em>draft</em> trip is checked against: the attached vehicle's current
     * effective capacity, or unlimited when no vehicle is attached yet.
     *
     * <p>Uses the display-grade lookup rather than {@code findAssignable} on purpose: a vehicle
     * that went into maintenance after being attached must still report the capacity the plan was
     * built on, so the planner sees a coherent board. Whether that vehicle may still run the trip
     * is re-checked at confirmation, where it belongs.
     */
    private CapacityLimits liveLimits(Trip trip, UUID companyId) {
        if (trip.vehicleId() == null) {
            return CapacityLimits.unlimited();
        }
        VehicleCapacityReference vehicle =
                vehicleLookupPort.findAllInCompany(Set.of(trip.vehicleId()), companyId).get(trip.vehicleId());
        return vehicle == null ? CapacityLimits.unlimited() : CapacityLimits.of(vehicle);
    }

    private static void requireCurrentVersion(String what, long persisted, Long requested) {
        if (requested == null) {
            throw new InvalidRequestException("version is required to modify a " + what + ".");
        }
        if (requested != persisted) {
            throw new ConflictException("This " + what
                    + " was changed by someone else since it was loaded. Reload and try again.");
        }
    }

    private Trip save(Trip trip) {
        try {
            return tripRepository.saveAndFlush(trip);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This trip was changed by someone else since it was loaded. Reload and try again.");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
