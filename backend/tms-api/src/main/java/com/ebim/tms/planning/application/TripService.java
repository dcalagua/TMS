package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("shipmentNumber", "planningDate", "tripNumber", "status", "plannedDepartureAt",
                    "actualDepartureAt", "createdAt", "updatedAt");

    /**
     * The states in which the driver may still be named or swapped: everything up to the moment
     * the vehicle leaves. Wider than the draft-only window vehicle and route edits get, and
     * deliberately so - see {@link Trip#assignDriver}.
     */
    private static final Set<TripStatus> DRIVER_ASSIGNABLE_STATES =
            Set.of(TripStatus.DRAFT, TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH);

    private final TripRepository tripRepository;
    private final PlanningRunRepository planningRunRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final TripAssignmentService assignments;
    private final OrderPlanningPort orderPlanningPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final DriverLookupPort driverLookupPort;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final PlanningCapacityService capacityService;
    private final TripViewAssembler assembler;
    private final ShipmentEventPublisher events;
    private final TripTenderService tenders;
    private final TripAlertPublisher alerts;
    private final CompanySettingsPort companySettingsPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public TripService(TripRepository tripRepository, PlanningRunRepository planningRunRepository,
            TripOrderAssignmentRepository assignmentRepository, TripAssignmentService assignments,
            OrderPlanningPort orderPlanningPort, VehicleLookupPort vehicleLookupPort,
            DriverLookupPort driverLookupPort, RouteTemplateLookupPort routeTemplateLookupPort,
            PlanningCapacityService capacityService, TripViewAssembler assembler, ShipmentEventPublisher events,
            TripTenderService tenders, TripAlertPublisher alerts, CompanySettingsPort companySettingsPort,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignments = assignments;
        this.orderPlanningPort = orderPlanningPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.driverLookupPort = driverLookupPort;
        this.routeTemplateLookupPort = routeTemplateLookupPort;
        this.capacityService = capacityService;
        this.assembler = assembler;
        this.events = events;
        this.tenders = tenders;
        this.alerts = alerts;
        this.companySettingsPort = companySettingsPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    /**
     * The Trips screen's list: this company's trips across runs, filtered and paginated.
     *
     * <p>A planning run is the wrong entry point for execution - a dispatcher's day is "everything
     * leaving today", which spans every run that produced a trip for that date, and the board can
     * only ever show one. This is the same set of rows the board shows, indexed by day instead of
     * by plan.
     *
     * <p>Costs a fixed number of queries for a whole page: the page itself plus
     * {@link TripViewAssembler}'s batched lookups, never one query per trip.
     */
    @Transactional(readOnly = true)
    public PageResponse<TripView> list(CompanyScope scope, TripFilter filter, PageQuery pageQuery) {
        Page<Trip> page = tripRepository.findAll(
                TripSpecifications.matching(scope.companyId(), filter), toPageable(pageQuery));
        List<TripView> content = assembler.toViews(page.getContent(), scope.companyId());
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
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

        ShipmentTimeRules.requireDepartureOnPlanningDate(request.plannedDepartureAt(), run.planningDate(),
                scope.timeZone(), "This trip");

        UUID actorId = auditActorProvider.requireAppUserId();
        UUID carrierId = null;
        if (request.vehicleId() != null) {
            carrierId = requireAssignableVehicle(scope, request.vehicleId()).carrierId();
            // No trip id exists yet, so nothing is excluded - the form of the check that says so.
            requireVehicleNotDoubleBooked(scope, request.vehicleId(), run.planningDate());
        }
        Trip trip = new Trip(scope.companyId(), run.id(), run.planningDate(),
                tripRepository.maxTripNumber(run.id()) + 1, generateShipmentNumber(scope), request.vehicleId(), carrierId,
                request.plannedDepartureAt(), actorId);
        Trip saved = saveWithDoubleBookingBackstop(trip, "vehicle");
        auditRecorder.record(scope, AuditAggregateType.TRIP, saved.id(), AuditAction.CREATE,
                Map.of("shipmentNumber", saved.shipmentNumber()));
        return assembler.toDetail(saved, scope.companyId());
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

        ShipmentTimeRules.requireDepartureOnPlanningDate(request.plannedDepartureAt(), trip.planningDate(),
                scope.timeZone(), "Trip " + trip.tripNumber());

        VehicleCapacityReference vehicle = requireAssignableVehicle(scope, request.vehicleId());
        CapacityLoad load = assignments.currentLoad(trip.id());
        capacityService.requireWithinCapacity(
                "Vehicle " + vehicle.code() + " cannot take what is already planned on trip " + trip.tripNumber(),
                CapacityLimits.of(vehicle), load);
        requireVehicleNotDoubleBooked(scope, vehicle.id(), trip.planningDate(), trip.id());
        requireDriverStillCompatible(scope, trip, vehicle.carrierId());

        trip.assignVehicle(vehicle.id(), vehicle.carrierId(), request.plannedDepartureAt(),
                auditActorProvider.requireAppUserId());
        Trip saved = saveWithDoubleBookingBackstop(trip, "vehicle");
        auditRecorder.record(scope, AuditAggregateType.TRIP, saved.id(), AuditAction.VEHICLE_CHANGE,
                Map.of("vehicleId", vehicle.id().toString()));
        return assembler.toDetail(saved, scope.companyId());
    }

    /**
     * Names the driver who will run this shipment, or clears the name when {@code driverId} is
     * null.
     *
     * <p>Allowed from {@code DRAFT}, {@code CONFIRMED} and {@code READY_FOR_DISPATCH}, unlike the
     * vehicle and route edits above, which stop at the draft: a driver calling in sick at 05:00 on
     * a shipment confirmed last night is the ordinary case, and nothing a plan was <em>validated
     * against</em> changes when the person at the wheel does ({@link Trip#assignDriver} spells the
     * asymmetry out). Once the vehicle has left, who is driving stops being a plan and becomes a
     * fact, so it is refused.
     *
     * <p>Four rules, each with its own sentence for the caller: the driver must be active and in
     * this company, their licence must not have run out by the day they are meant to drive, they
     * must not already be out on another trip that day, and if both the trip and the driver name a
     * carrier the two must be the same one.
     */
    @Transactional
    public TripDetailView updateDriver(CompanyScope scope, UUID tripId, TripDriverRequest request) {
        Trip trip = lockedDriverAssignableTrip(scope, tripId);
        requireCurrentVersion("trip", trip.version(), request.version());

        UUID actorId = auditActorProvider.requireAppUserId();
        if (request.driverId() == null) {
            trip.assignDriver(null, actorId);
            Trip cleared = save(trip);
            auditRecorder.record(scope, AuditAggregateType.TRIP, cleared.id(), AuditAction.DRIVER_CHANGE,
                    Map.of("shipmentNumber", cleared.shipmentNumber(), "driverId", "none"));
            return assembler.toDetail(cleared, scope.companyId());
        }

        DriverReference driver = driverLookupPort.findAssignable(request.driverId(), scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "driverId does not reference an active driver in this company."));
        requireValidLicenceOn(driver, trip.planningDate());
        requireCarrierCompatible(driver, trip.carrierId());
        requireDriverNotDoubleBooked(scope, driver.id(), trip.planningDate(), trip.id());

        trip.assignDriver(driver.id(), actorId);
        Trip saved = saveWithDoubleBookingBackstop(trip, "driver");
        auditRecorder.record(scope, AuditAggregateType.TRIP, saved.id(), AuditAction.DRIVER_CHANGE,
                Map.of("shipmentNumber", saved.shipmentNumber(), "driverId", driver.id().toString()));
        // A licence that has run out was refused above; one that runs out inside the warning window
        // is legal and is exactly what nobody notices (V32). Raised after the assignment succeeded,
        // and keyed on the driver and their expiry date, so planning the same person all week is
        // one warning - see TripAlertPublisher.driverAssigned.
        alerts.driverAssigned(scope, saved, driver, OffsetDateTime.now());
        return assembler.toDetail(saved, scope.companyId());
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
        Trip saved = save(trip);
        auditRecorder.record(scope, AuditAggregateType.TRIP, saved.id(), AuditAction.ASSIGN_ORDER,
                Map.of("orderNumber", order.orderNumber()));
        return assembler.toDetail(saved, scope.companyId());
    }

    @Transactional
    public TripDetailView removeOrder(CompanyScope scope, UUID tripId, UUID orderId, String reason) {
        Trip trip = lockedDraftTripForAssignment(scope, tripId);
        TripOrderAssignment assignment = requireActiveAssignment(trip, orderId);

        UUID actorId = auditActorProvider.requireAppUserId();
        assignments.closeAndRelease(assignment, blankToNull(reason), actorId);
        assignments.refreshStops(trip, scope.companyId(), actorId);
        trip.touch(actorId);
        Trip saved = save(trip);
        auditRecorder.record(scope, AuditAggregateType.TRIP, saved.id(), AuditAction.REMOVE_ORDER,
                Map.of("orderId", orderId.toString()));
        return assembler.toDetail(saved, scope.companyId());
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
        Trip savedSource = save(source);
        auditRecorder.record(scope, AuditAggregateType.TRIP, savedSource.id(), AuditAction.MOVE_ORDER,
                Map.of("orderNumber", order.orderNumber(), "targetTripId", target.id().toString()));
        return assembler.toDetail(savedSource, scope.companyId());
    }

    /**
     * Points the shipment at a master route, or clears the pointer.
     *
     * <p>Everything this does <em>not</em> do is the design: it does not create a stop for a
     * destination the route names and nothing is delivered to, does not remove a stop the route
     * omits, and does not re-apply itself when the route master is later edited. A master route
     * is a reusable corridor and a shipment is one dated instance of real orders; the link
     * between them is a planner's note about where the shape came from, plus an optional
     * one-time reordering of the stops the shipment already has. See
     * {@code docs/domain/SHIPMENT_V2.md}, "Route master interaction", and {@link Trip#applyRoute}.
     */
    @Transactional
    public TripDetailView updateRoute(CompanyScope scope, UUID tripId, TripRouteRequest request) {
        Trip trip = lockedDraftTrip(scope, tripId);
        requireCurrentVersion("trip", trip.version(), request.version());

        UUID actorId = auditActorProvider.requireAppUserId();
        if (request.routeId() == null) {
            trip.applyRoute(null, List.of(), actorId);
            return assembler.toDetail(save(trip), scope.companyId());
        }

        RouteTemplate route = routeTemplateLookupPort.findActiveInCompany(request.routeId(), scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "routeId does not reference an active route in this company."));
        PlanningRun run = requireDraftRun(scope, trip.planningRunId());
        if (!route.originId().equals(run.originId())) {
            throw new InvalidRequestException("Route " + route.code()
                    + " departs from a different origin than planning run " + run.planNumber() + ".");
        }

        trip.applyRoute(route.id(), request.applySequence() ? route.destinationIds() : List.of(), actorId);
        return assembler.toDetail(save(trip), scope.companyId());
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

    /**
     * Cancels a trip and returns every order on it to the eligible pool.
     *
     * <p>Legal from {@code DRAFT}, {@code CONFIRMED} and {@code READY_FOR_DISPATCH} - the states
     * before the vehicle leaves. {@code TripStatus} owns that list; this method only asks it.
     *
     * <p>Two things follow from a trip having been confirmed before it was cancelled, and both are
     * conditional on {@code confirmedAt} rather than on the status the trip is in now, because by
     * the time either runs the status is already {@code CANCELLED}:
     *
     * <ol>
     *   <li>a reason is required. Discarding a sketch needs no explanation; withdrawing a shipment
     *       a carrier was told about does, and it is the only record of why;</li>
     *   <li>a {@code SHIPMENT_CANCELLED} event is published. A partner that was handed a confirmed
     *       shipment has to be told it is not happening - the case migration V20 wrote its CHECK
     *       for and could not yet produce.</li>
     * </ol>
     *
     * <p>A third thing happens unconditionally: any live carrier tender is withdrawn (migration
     * V31). Unconditional because it costs one indexed query on a shipment that has none, and
     * because the alternative - a carrier accepting a shipment that is not happening - is the worst
     * outcome this feature can produce.</p>
     *
     * <p>Lives here rather than in {@code TripExecutionService} because cancelling is where the
     * order releases happen, and those are this class's ({@code TripAssignmentService}'s) business,
     * not the lifecycle's. It is also one action from a user's point of view, and splitting it
     * across two endpoints by the state it starts from would push the transition table into the
     * frontend.
     */
    @Transactional
    public TripDetailView cancel(CompanyScope scope, UUID tripId, PlanningActionRequest request) {
        Trip trip = lockedCancellableTrip(scope, tripId);
        // Before the version check: a retry of a cancellation that already succeeded is answered
        // with the cancelled trip, not with "reload and try again". Same rule, same reasoning as
        // TripExecutionService's other transitions.
        if (trip.status() == TripStatus.CANCELLED) {
            return assembler.toDetail(trip, scope.companyId());
        }
        requireCurrentVersion("trip", trip.version(), request.version());

        boolean wasCommitted = trip.confirmedAt() != null;
        String reason = blankToNull(request.reason());
        if (wasCommitted && reason == null) {
            throw new InvalidRequestException(
                    "reason is required to cancel a trip that has already been confirmed.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        assignments.releaseAll(trip, reason == null ? "Trip cancelled" : reason, actorId);
        trip.cancel(reason, actorId);
        Trip saved = save(trip);
        auditRecorder.record(scope, AuditAggregateType.TRIP, saved.id(), AuditAction.CANCEL,
                Map.of("shipmentNumber", saved.shipmentNumber()));
        if (wasCommitted) {
            events.publish(scope, saved, ShipmentEventType.SHIPMENT_CANCELLED, saved.cancelledAt(),
                    Map.of("reason", reason));
        }
        // After the cancellation and after its event, so the timeline reads cause then effect: the
        // shipment was cancelled, therefore the offer on it was withdrawn. Load-bearing rather than
        // tidy-up - without it a carrier could accept a shipment that is not happening, which is the
        // one way tendering could produce a truck sent to a depot for nothing (migration V31).
        tenders.withdrawOpen(scope, saved,
                "Shipment " + saved.shipmentNumber() + " was cancelled" + (reason == null ? "." : ": " + reason));
        return assembler.toDetail(saved, scope.companyId());
    }

    private Trip find(CompanyScope scope, UUID tripId) {
        return tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    /** Locks a draft trip for an edit whose version the caller has already presented. */
    private Trip lockedDraftTrip(CompanyScope scope, UUID tripId) {
        Trip trip = lockedTrip(scope, tripId);
        requireDraft(trip);
        return trip;
    }

    /**
     * Locks a trip that cancellation may still reach, deciding that from {@link TripStatus}'s own
     * transition table rather than from a second list of states kept here. A trip already
     * {@code CANCELLED} passes: {@link #cancel} answers a retry with the cancelled trip.
     */
    private Trip lockedCancellableTrip(CompanyScope scope, UUID tripId) {
        Trip trip = lockedTrip(scope, tripId);
        if (trip.status() != TripStatus.CANCELLED && !trip.status().canTransitionTo(TripStatus.CANCELLED)) {
            throw new ConflictException(
                    "Trip " + trip.tripNumber() + " is " + trip.status() + " and can no longer be cancelled.");
        }
        return trip;
    }

    /**
     * Locks a trip whose driver may still be changed. Refused rather than silently ignored once
     * the truck has left: a dispatcher who thinks they reassigned a driver on a shipment that is
     * already on the road has a worse problem than a 409.
     */
    private Trip lockedDriverAssignableTrip(CompanyScope scope, UUID tripId) {
        Trip trip = lockedTrip(scope, tripId);
        if (!DRIVER_ASSIGNABLE_STATES.contains(trip.status())) {
            throw new ConflictException("Trip " + trip.tripNumber() + " is " + trip.status()
                    + " and its driver can no longer be changed.");
        }
        return trip;
    }

    private Trip lockedTrip(CompanyScope scope, UUID tripId) {
        return tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
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
     * The V1 double-booking invariant (docs/database/DATA_MODEL.md section 10, migration V16):
     * one active (DRAFT or CONFIRMED) trip per vehicle per planning date. Checked here with a
     * caller-facing message before every write that sets a trip's vehicle; the database's own
     * copy, {@code uq_trip_vehicle_active_planning_date}, is the concurrency backstop for two
     * planners racing to book the same vehicle on the same day - the same relationship
     * {@link #requireNotAlreadyAssigned} has with the assignment table's partial unique index.
     */
    private void requireVehicleNotDoubleBooked(CompanyScope scope, UUID vehicleId, LocalDate planningDate,
            UUID excludedTripId) {
        if (tripRepository.existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNotAndIdNot(
                scope.companyId(), vehicleId, planningDate, TripStatus.CANCELLED, excludedTripId)) {
            throw new ConflictException(
                    "This vehicle is already booked on another active trip for " + planningDate + ".");
        }
    }

    /**
     * The same rule from a caller that is creating a trip, so there is no trip of its own to
     * exclude. Separate from the four-argument form rather than passing a placeholder id: "there
     * is nothing to exclude" is the fact, and a freshly generated UUID standing in for it reads
     * as an exclusion that happens never to match.
     */
    private void requireVehicleNotDoubleBooked(CompanyScope scope, UUID vehicleId, LocalDate planningDate) {
        if (tripRepository.existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNot(
                scope.companyId(), vehicleId, planningDate, TripStatus.CANCELLED)) {
            throw new ConflictException(
                    "This vehicle is already booked on another active trip for " + planningDate + ".");
        }
    }

    /**
     * The driver double-booking invariant (migration V26): one active trip per driver per planning
     * date. The same shape as {@link #requireVehicleNotDoubleBooked} and for a stricter physical
     * reason - a truck could in principle be re-crewed and sent out twice in a day, a person
     * cannot be in two cabs at once.
     */
    private void requireDriverNotDoubleBooked(CompanyScope scope, UUID driverId, LocalDate planningDate,
            UUID excludedTripId) {
        if (tripRepository.existsByCompanyIdAndDriverIdAndPlanningDateAndStatusNotAndIdNot(
                scope.companyId(), driverId, planningDate, TripStatus.CANCELLED, excludedTripId)) {
            throw new ConflictException(
                    "This driver is already assigned to another active trip for " + planningDate + ".");
        }
    }

    /**
     * A licence has to be valid on the day it will be used, which is the trip's planning date and
     * not today: a plan built on Friday for Monday must be refused if the licence lapses over the
     * weekend, and accepting it because it is still valid at the moment of planning would put an
     * illegal driver on the road on the one day it mattered.
     *
     * <p>A driver with no recorded expiry passes - see {@code DriverLicenseStatus}. Refused as a
     * 400 rather than a 409 for the same reason an unavailable vehicle is: the request named
     * something that cannot be used, which is not a race with another planner.
     */
    private static void requireValidLicenceOn(DriverReference driver, LocalDate planningDate) {
        if (driver.licenseStatusOn(planningDate) == DriverLicenseStatus.EXPIRED) {
            throw new InvalidRequestException("Driver " + driver.code() + " has a licence that expired on "
                    + driver.licenseExpiresOn() + " and cannot be assigned to a trip planned for "
                    + planningDate + ".");
        }
    }

    /**
     * A driver employed by one carrier must not be planned onto another carrier's vehicle.
     *
     * <p>Checked only when <em>both</em> sides name a carrier. A driver with none is a company's
     * own staff member, and lending them a subcontracted truck for a day is a real arrangement
     * that TMS has no business refusing; a trip with none has no vehicle attached yet, and the
     * check runs again from {@link #updateVehicle} when one is. That "both non-null" shape is also
     * why this is a service rule and not a database constraint - a composite foreign key cannot
     * express it.
     */
    private static void requireCarrierCompatible(DriverReference driver, UUID tripCarrierId) {
        if (driver.carrierId() == null || tripCarrierId == null || driver.carrierId().equals(tripCarrierId)) {
            return;
        }
        throw new InvalidRequestException("Driver " + driver.code()
                + " works for a different carrier than the vehicle planned on this trip.");
    }

    /**
     * The other direction of {@link #requireCarrierCompatible}: swapping in a vehicle from another
     * carrier must not quietly leave an incompatible driver on the trip. Resolved with the
     * display-grade lookup rather than {@code findAssignable}, on purpose - a driver deactivated
     * after being assigned should not make an unrelated vehicle swap impossible; only their
     * <em>carrier</em> is at issue here, and {@code TripExecutionService} is where being inactive
     * stops the shipment.
     */
    private void requireDriverStillCompatible(CompanyScope scope, Trip trip, UUID newCarrierId) {
        if (trip.driverId() == null || newCarrierId == null) {
            return;
        }
        DriverReference driver =
                driverLookupPort.findAllInCompany(Set.of(trip.driverId()), scope.companyId()).get(trip.driverId());
        if (driver != null) {
            requireCarrierCompatible(driver, newCarrierId);
        }
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

    /**
     * {@link #save} plus a translation for the two double-booking indexes
     * ({@code uq_trip_vehicle_active_planning_date}, {@code uq_trip_driver_active_planning_date}):
     * the concurrency backstop for {@link #requireVehicleNotDoubleBooked} and
     * {@link #requireDriverNotDoubleBooked}, the moment two planners who each passed their own
     * pre-check both try to book the same vehicle - or the same person - on the same day. Used
     * only by the three writes that set one of those columns ({@code create},
     * {@code updateVehicle}, {@code updateDriver}); every other {@code save(trip)} call cannot
     * touch either index.
     *
     * @param subject which of the two the caller was writing, so the sentence names the thing the
     *     planner actually chose rather than guessing from the constraint
     */
    private Trip saveWithDoubleBookingBackstop(Trip trip, String subject) {
        try {
            return save(trip);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("This " + subject + " is already booked on another active trip for "
                    + trip.planningDate() + ".");
        }
    }

    /**
     * The shipment's external identity, formatted from {@code tms.shipment_number_seq} - the same
     * idiom {@code PlanningRunService.generatePlanNumber} uses. Drawn from a sequence rather than
     * from {@code MAX(shipment_number) + 1} so two planners creating a trip at the same instant
     * cannot be handed the same number; the {@code uq_trip_shipment_number} constraint is there
     * for the case a raw insert bypasses this method entirely.
     *
     * <p>The prefix became the company's with migration V34
     * ({@code tms.company_settings.shipment_number_prefix}); the sequence behind it stayed
     * installation-wide, which is what keeps the constraint satisfiable no matter what two tenants
     * choose to call their shipments. A missing settings row resolves to {@code "SH-"} - the literal
     * this line used to hold - so nothing an installation has already issued changes shape.
     */
    private String generateShipmentNumber(CompanyScope scope) {
        String prefix = companySettingsPort.settingsOf(scope.companyId()).shipmentNumberPrefix();
        return prefix + String.format(Locale.ROOT, "%08d", tripRepository.nextShipmentNumberValue());
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Newest planning date first by default, with the trip number as the tie-breaker: a dispatcher
     * opens the Trips screen to see today, and two trips of the same day should come back in the
     * order the planner numbered them rather than in whatever order the page boundary produced.
     */
    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        if (terms.isEmpty()) {
            return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(),
                    Sort.by(Sort.Order.desc("planningDate"), Sort.Order.asc("tripNumber")));
        }
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), Sort.by(terms.stream()
                .map(term -> new Sort.Order(
                        term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                .toList()));
    }
}
