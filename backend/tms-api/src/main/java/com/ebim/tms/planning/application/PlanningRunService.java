package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.PlanningRunStatus;
import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.PlanningRunSpecifications;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.PlannableOrderQuery;
import com.ebim.tms.shared.reference.TripCostEstimationPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planning run use cases: find the orders waiting to be planned, open a run for a company/origin/
 * date, read the board, confirm the plan or discard it.
 *
 * <p>Confirmation is the only place a plan becomes binding, and it revalidates everything from
 * scratch rather than trusting what the board looked like when the planner last loaded it: each
 * trip must still have an active vehicle, a departure time, at least one order and a load that
 * fits - and the capacity it is validated against is then frozen onto the trip (see
 * {@code docs/domain/CAPACITY_MODEL.md}).
 */
@Service
public class PlanningRunService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("planNumber", "planningDate", "status", "createdAt", "updatedAt");

    private final PlanningRunRepository planningRunRepository;
    private final TripRepository tripRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final ShipmentEventPublisher events;
    private final TripAssignmentService assignments;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final OrderPlanningPort orderPlanningPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final PlanningCapacityService capacityService;
    private final TripCostEstimationPort tripCostEstimationPort;
    private final TripViewAssembler assembler;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public PlanningRunService(PlanningRunRepository planningRunRepository, TripRepository tripRepository,
            TripOrderAssignmentRepository assignmentRepository, ShipmentEventPublisher events,
            TripAssignmentService assignments, OriginLookupPort originLookupPort,
            DestinationLookupPort destinationLookupPort, OrderPlanningPort orderPlanningPort,
            VehicleLookupPort vehicleLookupPort, PlanningCapacityService capacityService,
            TripCostEstimationPort tripCostEstimationPort, TripViewAssembler assembler,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.planningRunRepository = planningRunRepository;
        this.tripRepository = tripRepository;
        this.assignmentRepository = assignmentRepository;
        this.events = events;
        this.assignments = assignments;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.orderPlanningPort = orderPlanningPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.capacityService = capacityService;
        this.tripCostEstimationPort = tripCostEstimationPort;
        this.assembler = assembler;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    /**
     * The orders a planner may still put on a trip: {@code READY_FOR_PLANNING}, in this company,
     * paginated, with destinations resolved in one batched lookup and no order lines anywhere
     * near the response.
     */
    @Transactional(readOnly = true)
    public PageResponse<EligibleOrderView> eligibleOrders(
            CompanyScope scope, EligibleOrderFilter filter, PageQuery pageQuery) {
        PlannableOrderQuery query = new PlannableOrderQuery(scope.companyId(), filter.originId(),
                filter.destinationId(), filter.serviceDate(), filter.orderNumber());
        PageResponse<PlannableOrder> page = orderPlanningPort.searchAssignable(query, pageQuery);

        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(
                page.content().stream().map(PlannableOrder::destinationId).collect(Collectors.toSet()),
                scope.companyId());
        List<EligibleOrderView> content = page.content().stream()
                .map(order -> EligibleOrderView.from(order, destinations.get(order.destinationId())))
                .toList();
        return new PageResponse<>(content, page.page(), page.size(), page.totalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanningRunView> list(CompanyScope scope, PlanningRunFilter filter, PageQuery pageQuery) {
        var specification = PlanningRunSpecifications.matching(scope.companyId(), filter.planNumber(),
                filter.originId(), filter.planningDateFrom(), filter.planningDateTo(), filter.status());
        Page<PlanningRun> page = planningRunRepository.findAll(specification, toPageable(pageQuery));
        List<PlanningRun> runs = page.getContent();

        Map<UUID, MasterReference> origins = originLookupPort.findAllInCompany(
                runs.stream().map(PlanningRun::originId).collect(Collectors.toSet()), scope.companyId());
        List<UUID> runIds = runs.stream().map(PlanningRun::id).toList();
        Map<UUID, Long> tripCounts = tripCounts(runIds);
        Map<UUID, Long> orderCounts = assignedOrderCounts(runIds);

        List<PlanningRunView> content = runs.stream()
                .map(run -> PlanningRunView.from(run, origins.get(run.originId()),
                        tripCounts.getOrDefault(run.id(), 0L), orderCounts.getOrDefault(run.id(), 0L)))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PlanningRunDetailView get(CompanyScope scope, UUID id) {
        return toDetail(scope, find(scope, id));
    }

    @Transactional
    public PlanningRunDetailView create(CompanyScope scope, PlanningRunRequest request) {
        MasterReference origin = originLookupPort.findActiveInCompany(request.originId(), scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "originId does not reference an active origin in this company."));
        if (planningRunRepository.existsByCompanyIdAndOriginIdAndPlanningDateAndStatus(
                scope.companyId(), request.originId(), request.planningDate(), PlanningRunStatus.DRAFT)) {
            throw openRunConflict(origin, request);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        PlanningRun run = new PlanningRun(scope.companyId(), generatePlanNumber(), request.originId(),
                request.planningDate(), blankToNull(request.notes()), actorId);
        PlanningRun saved = saveOrConflict(run, origin, request);
        auditRecorder.record(scope, AuditAggregateType.PLANNING_RUN, saved.id(), AuditAction.CREATE,
                Map.of("planNumber", saved.planNumber()));
        return toDetail(scope, saved);
    }

    /**
     * Confirms the plan. Everything is revalidated here rather than assumed from the board the
     * planner was looking at, because minutes may have passed and a vehicle may have gone into
     * maintenance in the meantime. Each trip is locked while it is checked, so a concurrent
     * assignment cannot slip in between the check and the freeze.
     */
    @Transactional
    public PlanningRunDetailView confirm(CompanyScope scope, UUID id, PlanningActionRequest request) {
        PlanningRun run = requireDraft(find(scope, id));
        requireCurrentVersion(run, request.version());

        List<Trip> plannedTrips = tripRepository.findByPlanningRunIdOrderByTripNumberAsc(run.id()).stream()
                .filter(trip -> trip.status() != TripStatus.CANCELLED)
                .toList();
        if (plannedTrips.isEmpty()) {
            throw new ConflictException(
                    "Planning run " + run.planNumber() + " has no trips to confirm.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        // Locked by trip id, then validated in trip-number order: the lock order has to be the
        // same one TripService.moveOrder uses, or a confirm and a concurrent move between two
        // trips of this run would each hold the lock the other is waiting for. The error message
        // a planner reads still follows the trip numbers they see on the board.
        Map<UUID, Trip> locked = lockTrips(scope, plannedTrips);
        for (Trip unlocked : plannedTrips) {
            confirmTrip(scope, run, locked.get(unlocked.id()), actorId);
        }

        run.confirm(actorId);
        PlanningRun saved = save(run);
        auditRecorder.record(scope, AuditAggregateType.PLANNING_RUN, saved.id(), AuditAction.CONFIRM,
                Map.of("planNumber", saved.planNumber(), "tripCount", plannedTrips.size()));
        return toDetail(scope, saved);
    }

    /**
     * Discards a draft plan: every trip is cancelled and every order on it returns to the eligible
     * pool, so nothing is left stranded in {@code PLANNED} with no trip to run it.
     */
    @Transactional
    public PlanningRunDetailView cancel(CompanyScope scope, UUID id, PlanningActionRequest request) {
        PlanningRun run = requireDraft(find(scope, id));
        requireCurrentVersion(run, request.version());

        UUID actorId = auditActorProvider.requireAppUserId();
        String reason = blankToNull(request.reason());
        List<Trip> openTrips = tripRepository.findByPlanningRunIdOrderByTripNumberAsc(run.id()).stream()
                .filter(trip -> trip.status() != TripStatus.CANCELLED)
                .toList();
        Map<UUID, Trip> locked = lockTrips(scope, openTrips);
        for (Trip unlocked : openTrips) {
            Trip trip = locked.get(unlocked.id());
            assignments.releaseAll(trip, reason == null ? "Planning run cancelled" : reason, actorId);
            trip.cancel(reason, actorId);
            tripRepository.saveAndFlush(trip);
        }

        run.cancel(reason, actorId);
        PlanningRun saved = save(run);
        auditRecorder.record(scope, AuditAggregateType.PLANNING_RUN, saved.id(), AuditAction.CANCEL,
                Map.of("planNumber", saved.planNumber()));
        return toDetail(scope, saved);
    }

    /**
     * Takes the row lock of every given trip, always in ascending trip-id order.
     *
     * <p>The order is the contract, not an implementation detail: {@code TripService.moveOrder}
     * locks its two trips by id for the same reason, and a run-wide operation that locked by trip
     * number instead would produce the classic ABBA deadlock against a concurrent move between
     * the same two trips. See {@code TripRepository.findByIdAndCompanyIdForUpdate} and
     * {@code docs/domain/PLANNING_MANUAL_V1.md}, "Concurrency".
     *
     * @return the locked, managed trips by id, so callers can still iterate in whatever order
     *     their user-facing messages need
     */
    private Map<UUID, Trip> lockTrips(CompanyScope scope, List<Trip> trips) {
        Map<UUID, Trip> locked = new LinkedHashMap<>();
        trips.stream().map(Trip::id).sorted().forEach(tripId ->
                locked.put(tripId, tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Trip not found."))));
        return locked;
    }

    private void confirmTrip(CompanyScope scope, PlanningRun run, Trip trip, UUID actorId) {
        String label = "Trip " + trip.tripNumber() + " of planning run " + run.planNumber();
        if (trip.vehicleId() == null) {
            throw new ConflictException(label + " has no vehicle assigned.");
        }
        if (trip.plannedDepartureAt() == null) {
            throw new ConflictException(label + " has no planned departure date and time.");
        }
        // Re-checked here and not only where the departure was typed: the run's planning date and
        // the trip's departure were validated against each other when each was written, but
        // confirmation is the moment the plan becomes binding and is the last place a stored
        // inconsistency (a row written before this rule existed, or by a raw SQL fix) can be
        // caught before a driver is dispatched on the wrong day.
        ShipmentTimeRules.requireDepartureOnPlanningDate(
                trip.plannedDepartureAt(), run.planningDate(), scope.timeZone(), label);
        VehicleCapacityReference vehicle = vehicleLookupPort.findAssignable(trip.vehicleId(), scope.companyId())
                .orElseThrow(() -> new ConflictException(
                        label + " is assigned a vehicle that is no longer active and available."));

        CapacityLoad load = assignments.currentLoad(trip.id());
        if (load.orderCount() == 0) {
            throw new ConflictException(label + " has no orders assigned.");
        }
        capacityService.requireWithinCapacity(label + " cannot be confirmed", CapacityLimits.of(vehicle), load);
        requireOrdersStillFitRun(scope, run, trip, label);

        // Idempotent, and the reason a confirmed trip's stop list always matches its assignments:
        // re-synchronised here rather than trusted to have stayed in step. refreshStops also
        // asserts that the resulting stops cover exactly the destinations the assignments deliver
        // to, so a confirmed shipment cannot be missing one.
        assignments.refreshStops(trip, scope.companyId(), actorId);
        trip.confirm(vehicle.maxWeightKg(), vehicle.maxVolumeM3(), vehicle.maxPallets(), actorId);
        tripRepository.saveAndFlush(trip);

        // Written in THIS transaction, not after commit - the whole point of a transactional
        // outbox is that the event and the state change it describes share one atomic unit, so a
        // rollback anywhere else in this loop (a sibling trip failing confirmTrip) takes this row
        // down with it too. See tms.shipment_outbox_event's migration V20 comment and
        // docs/integrations/OUTBOUND_SHIPMENT_V1.md, "Change feed". ShipmentEventPublisher is the
        // one place that pairs the outbox row with its audit event, shared with the execution
        // transitions so the two cannot drift.
        events.publish(scope, trip, ShipmentEventType.SHIPMENT_CONFIRMED, OffsetDateTime.now(),
                Map.of("planNumber", run.planNumber(), "tripNumber", trip.tripNumber()));

        // Priced here because this is the moment the plan becomes binding, so it is the moment the
        // tariff in force should be recorded against it - a cost estimated tomorrow would be
        // estimated against whatever the rate cards say tomorrow. Best effort by contract
        // (TripCostEstimationPort): a company that has entered no tariffs, or a shipment no card
        // covers, still confirms. A real failure rolls this transaction back with everything else.
        tripCostEstimationPort.estimateOnConfirmation(scope, trip.id(), actorId);
    }

    /**
     * Re-checks, at the moment the plan becomes binding, that every order still on the trip is
     * still an order this run may carry: same origin, same service date.
     *
     * <p>Both were checked when the order was assigned. Neither is immutable afterwards - an
     * order's service date can be rescheduled and its destination or origin corrected while it
     * sits on a draft trip - and a confirmed shipment that departs from the wrong depot or on the
     * wrong day is exactly the failure this gate exists to prevent. One batched lookup for the
     * whole trip, not one per order.
     */
    private void requireOrdersStillFitRun(CompanyScope scope, PlanningRun run, Trip trip, String label) {
        List<TripOrderAssignment> active = assignments.activeAssignments(trip.id());
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(
                active.stream().map(TripOrderAssignment::orderId).collect(Collectors.toSet()), scope.companyId());
        for (TripOrderAssignment assignment : active) {
            PlannableOrder order = orders.get(assignment.orderId());
            if (order == null) {
                throw new ConflictException(label + " carries an order that no longer exists in this company.");
            }
            if (!order.originId().equals(run.originId())) {
                throw new ConflictException(label + " carries order " + order.orderNumber()
                        + ", which now departs from a different origin than the run.");
            }
            if (!order.serviceDate().equals(run.planningDate())) {
                throw new ConflictException(label + " carries order " + order.orderNumber()
                        + ", whose service date is now " + order.serviceDate() + " and not the run's planning date ("
                        + run.planningDate() + ").");
            }
        }
    }

    private PlanningRunDetailView toDetail(CompanyScope scope, PlanningRun run) {
        List<Trip> trips = tripRepository.findByPlanningRunIdOrderByTripNumberAsc(run.id());
        List<TripView> tripViews = assembler.toViews(trips, scope.companyId());
        MasterReference origin = originLookupPort.findAllInCompany(Set.of(run.originId()), scope.companyId())
                .get(run.originId());
        long tripCount = tripViews.stream().filter(view -> view.status() != TripStatus.CANCELLED).count();
        long orderCount = tripViews.stream()
                .filter(view -> view.status() != TripStatus.CANCELLED)
                .mapToLong(TripView::orderCount)
                .sum();
        return new PlanningRunDetailView(PlanningRunView.from(run, origin, tripCount, orderCount), tripViews);
    }

    private Map<UUID, Long> tripCounts(List<UUID> runIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (runIds.isEmpty()) {
            return counts;
        }
        for (TripRepository.TripCount count : tripRepository.countByPlanningRunIds(runIds, TripStatus.CANCELLED)) {
            counts.put(count.getRunId(), count.getTripCount());
        }
        return counts;
    }

    private Map<UUID, Long> assignedOrderCounts(List<UUID> runIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (runIds.isEmpty()) {
            return counts;
        }
        for (TripOrderAssignmentRepository.RunOrderCount count
                : assignmentRepository.countByPlanningRunIds(runIds, AssignmentStatus.ACTIVE)) {
            counts.put(count.getRunId(), count.getOrderCount());
        }
        return counts;
    }

    private PlanningRun find(CompanyScope scope, UUID id) {
        return planningRunRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Planning run not found."));
    }

    private static PlanningRun requireDraft(PlanningRun run) {
        if (!run.isDraft()) {
            throw new ConflictException("Planning run " + run.planNumber() + " is " + run.status()
                    + " and can no longer be modified.");
        }
        return run;
    }

    private static void requireCurrentVersion(PlanningRun run, Long requested) {
        if (requested == null) {
            throw new InvalidRequestException("version is required to modify a planning run.");
        }
        if (requested != run.version()) {
            throw new ConflictException(
                    "This planning run was changed by someone else since it was loaded. Reload and try again.");
        }
    }

    private String generatePlanNumber() {
        return "PL-" + String.format(Locale.ROOT, "%08d", planningRunRepository.nextPlanNumberValue());
    }

    private PlanningRun saveOrConflict(PlanningRun run, MasterReference origin, PlanningRunRequest request) {
        try {
            return planningRunRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException raced) {
            // uq_planning_run_open_scope: another planner opened the same origin/date first.
            throw openRunConflict(origin, request);
        }
    }

    private PlanningRun save(PlanningRun run) {
        try {
            return planningRunRepository.saveAndFlush(run);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException(
                    "This planning run was changed by someone else since it was loaded. Reload and try again.");
        }
    }

    private static ConflictException openRunConflict(MasterReference origin, PlanningRunRequest request) {
        return new ConflictException("An open planning run already exists for origin "
                + (origin == null ? "" : origin.code() + " ") + "on " + request.planningDate()
                + ". Confirm or cancel it before opening another.");
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.DESC, "planningDate")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
