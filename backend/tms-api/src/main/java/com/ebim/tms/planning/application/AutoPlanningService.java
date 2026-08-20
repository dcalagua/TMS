package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedOrder;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
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
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.PlannableOrderQuery;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.ServiceCalendarPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatic planning, end to end: gather the day's snapshot, ask a {@link PlanningEngine} for a
 * proposal, and write that proposal down as ordinary draft trips.
 *
 * <p>Three decisions define this class.
 *
 * <p><b>It writes through {@code TripService}, never around it.</b> Every proposed trip is created
 * and every proposed order assigned through the same use cases a planner's clicks go through, so
 * capacity limits, vehicle double-booking, stop derivation, the order lifecycle transition and the
 * audit trail all apply unchanged. An engine cannot produce a plan the product would refuse,
 * because the product is what writes it. It also means a proposal that is stale by the time it is
 * applied - an order someone else just took - degrades into that order being reported as
 * unplanned, not into a corrupt trip.
 *
 * <p><b>It never confirms.</b> The output is draft trips on a draft run. A planner reviews, edits
 * and confirms, exactly as with a hand-built plan. Automatic planning that confirmed itself would
 * be a robot dispatching trucks, which is not what anyone asked for.
 *
 * <p><b>Every order is accounted for.</b> Whatever went into the snapshot comes back either on a
 * trip or in {@code unplanned} with a reason, and {@link #assertEveryOrderAccountedFor} checks
 * that rather than trusting the engine. A planner who is told "9 trips created" and finds 40
 * orders quietly left behind has been misled by their own tool.
 */
@Service
public class AutoPlanningService {

    private static final Logger log = LoggerFactory.getLogger(AutoPlanningService.class);

    /**
     * How many eligible orders one automatic plan will look at. Not a page size - a deliberate
     * ceiling: beyond this the answer a planner needs is "split the day", not a proposal built
     * from a truncated backlog they cannot see. Exceeding it is refused rather than silently cut.
     */
    static final int MAX_ORDERS_PER_RUN = 1000;

    private final PlanningRunRepository planningRunRepository;
    private final TripRepository tripRepository;
    private final TripService tripService;
    private final PlanningEngine engine;
    private final OrderPlanningPort orderPlanningPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final ServiceCalendarPort serviceCalendarPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public AutoPlanningService(PlanningRunRepository planningRunRepository, TripRepository tripRepository,
            TripService tripService, PlanningEngine engine, OrderPlanningPort orderPlanningPort,
            VehicleLookupPort vehicleLookupPort, RouteTemplateLookupPort routeTemplateLookupPort,
            ServiceCalendarPort serviceCalendarPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.planningRunRepository = planningRunRepository;
        this.tripRepository = tripRepository;
        this.tripService = tripService;
        this.engine = engine;
        this.orderPlanningPort = orderPlanningPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.routeTemplateLookupPort = routeTemplateLookupPort;
        this.serviceCalendarPort = serviceCalendarPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Proposes without writing anything. Same snapshot, same engine, same result as {@link #apply}
     * would produce - a preview a planner can look at before committing to it.
     */
    @Transactional(readOnly = true)
    public AutoPlanView preview(CompanyScope scope, UUID runId) {
        PlanningRun run = requireDraftRun(scope, runId);
        Snapshot snapshot = snapshot(scope, run);
        PlanningProposal proposal = propose(snapshot);
        return AutoPlanView.preview(proposal, snapshot.orderNumbers(), snapshot.vehicleCodes());
    }

    /**
     * Proposes and writes the proposal as draft trips on the run.
     *
     * @param request carries the run's version, so a plan is never attached to a run someone else
     *                has just confirmed or cancelled - the same guard every other planning write
     *                uses
     */
    @Transactional
    public AutoPlanView apply(CompanyScope scope, UUID runId, PlanningActionRequest request) {
        PlanningRun run = requireDraftRun(scope, runId);
        if (request.version() != null && request.version() != run.version()) {
            throw new ConflictException("This planning run has changed since it was loaded. Reload and try again.");
        }

        Snapshot snapshot = snapshot(scope, run);
        PlanningProposal proposal = propose(snapshot);
        if (proposal.trips().isEmpty()) {
            return AutoPlanView.applied(proposal, List.of(), snapshot.orderNumbers(), snapshot.vehicleCodes());
        }

        // The actor is resolved once and up front: a proposal that turned out to be unwritable
        // should fail before any trip exists, not after three of them do.
        auditActorProvider.requireAppUserId();

        List<TripDetailView> created = new ArrayList<>();
        List<UnplannedOrder> rejected = new ArrayList<>();
        for (ProposedTrip proposed : proposal.trips()) {
            TripDetailView trip = tripService.create(scope, run.id(),
                    new TripCreateRequest(proposed.vehicleId(), null, run.version()));
            UUID tripId = trip.trip().id();
            for (UUID orderId : proposed.orderIds()) {
                try {
                    trip = tripService.assignOrder(scope, tripId, new AssignOrderRequest(orderId));
                } catch (InvalidRequestException | ConflictException refused) {
                    // The snapshot was taken a moment ago and another planner may have moved
                    // faster. That is a report, not a failure: the trip keeps what it did get,
                    // and the order stays in the pool with a reason attached.
                    log.info("auto-plan: order {} refused on trip {}: {}",
                            orderId, tripId, refused.getMessage());
                    rejected.add(new UnplannedOrder(orderId, snapshot.orderNumbers().get(orderId),
                            UnplannedReason.NO_VEHICLE_AVAILABLE));
                }
            }
            created.add(trip);
        }

        auditRecorder.record(scope, AuditAggregateType.PLANNING_RUN, run.id(), AuditAction.AUTO_PLAN,
                Map.of("engine", proposal.engine(),
                        "tripsCreated", String.valueOf(created.size()),
                        "ordersConsidered", String.valueOf(snapshot.orderNumbers().size())));

        List<UnplannedOrder> unplanned = new ArrayList<>(proposal.unplanned());
        unplanned.addAll(rejected);
        PlanningProposal outcome = new PlanningProposal(proposal.engine(), proposal.trips(), unplanned);
        return AutoPlanView.applied(outcome, created, snapshot.orderNumbers(), snapshot.vehicleCodes());
    }

    // --- snapshot -------------------------------------------------------------------------

    /**
     * Everything the engine may see, plus the display names the view needs. Built once so preview
     * and apply cannot disagree about what the day looked like.
     */
    private record Snapshot(PlanningInput input, List<UnplannedOrder> excludedByCalendar,
            Map<UUID, String> orderNumbers, Map<UUID, String> vehicleCodes) {
    }

    private Snapshot snapshot(CompanyScope scope, PlanningRun run) {
        List<PlannableOrder> eligible = loadEligibleOrders(scope, run);
        List<VehicleCapacityReference> vehicles = loadFreeVehicles(scope, run);
        List<RouteTemplate> routes =
                routeTemplateLookupPort.findActiveByOriginInCompany(run.originId(), scope.companyId());

        Set<UUID> destinations =
                eligible.stream().map(PlannableOrder::destinationId).collect(Collectors.toSet());
        Set<UUID> serviceable =
                serviceCalendarPort.serviceableOn(destinations, run.planningDate(), scope.companyId());

        List<PlannableOrder> plannable = new ArrayList<>();
        List<UnplannedOrder> excluded = new ArrayList<>();
        for (PlannableOrder order : eligible) {
            if (serviceable.contains(order.destinationId())) {
                plannable.add(order);
            } else {
                excluded.add(new UnplannedOrder(order.id(), order.orderNumber(),
                        UnplannedReason.NOT_SERVICEABLE_ON_DATE));
            }
        }

        Map<UUID, String> orderNumbers = new LinkedHashMap<>();
        eligible.forEach(order -> orderNumbers.put(order.id(), order.orderNumber()));
        Map<UUID, String> vehicleCodes = new LinkedHashMap<>();
        vehicles.forEach(vehicle -> vehicleCodes.put(vehicle.id(), vehicle.code()));

        PlanningInput input = new PlanningInput(run.originId(), run.planningDate(), plannable, vehicles, routes);
        return new Snapshot(input, excluded, orderNumbers, vehicleCodes);
    }

    private PlanningProposal propose(Snapshot snapshot) {
        PlanningProposal proposal = engine.plan(snapshot.input());
        List<UnplannedOrder> unplanned = new ArrayList<>(snapshot.excludedByCalendar());
        unplanned.addAll(proposal.unplanned());
        PlanningProposal complete = new PlanningProposal(proposal.engine(), proposal.trips(), unplanned);
        assertEveryOrderAccountedFor(snapshot, complete);
        return complete;
    }

    /**
     * Every order in the snapshot is on a proposed trip or in the unplanned list - never neither,
     * never both. A silent leak here is the failure mode that matters: the planner sees a plausible
     * plan and finds the missing orders when the trucks have gone.
     */
    private static void assertEveryOrderAccountedFor(Snapshot snapshot, PlanningProposal proposal) {
        Set<UUID> planned = proposal.trips().stream()
                .flatMap(trip -> trip.orderIds().stream())
                .collect(Collectors.toSet());
        Set<UUID> reported = proposal.unplanned().stream()
                .map(UnplannedOrder::orderId)
                .collect(Collectors.toSet());

        for (UUID orderId : snapshot.orderNumbers().keySet()) {
            boolean isPlanned = planned.contains(orderId);
            if (isPlanned == reported.contains(orderId)) {
                throw new IllegalStateException("planning engine " + proposal.engine() + " left order " + orderId
                        + (isPlanned ? " both planned and unplanned" : " neither planned nor reported"));
            }
        }
    }

    /**
     * The run's eligible backlog: orders ready for planning, from this origin, for this date.
     * Read in pages of {@link PageQuery}'s maximum rather than one huge query, and refused above
     * {@link #MAX_ORDERS_PER_RUN} rather than truncated - a proposal built from a backlog the
     * planner cannot see the end of is worse than no proposal.
     */
    private List<PlannableOrder> loadEligibleOrders(CompanyScope scope, PlanningRun run) {
        PlannableOrderQuery query = new PlannableOrderQuery(
                scope.companyId(), run.originId(), null, run.planningDate(), null);

        List<PlannableOrder> all = new ArrayList<>();
        int page = 0;
        while (true) {
            PageResponse<PlannableOrder> slice =
                    orderPlanningPort.searchAssignable(query, new PageQuery(page, PageQuery.MAX_SIZE, null));
            all.addAll(slice.content());
            if (all.size() > MAX_ORDERS_PER_RUN) {
                throw new InvalidRequestException("This planning run has more than " + MAX_ORDERS_PER_RUN
                        + " eligible orders. Narrow the day with additional planning runs, or plan by hand.");
            }
            if (slice.content().isEmpty() || all.size() >= slice.totalElements()) {
                return all;
            }
            page++;
        }
    }

    /**
     * The vehicles a proposal may use: assignable in fleet's sense, and not already committed to
     * another trip on this planning date. Filtering here rather than letting {@code TripService}
     * refuse them keeps the engine's arithmetic honest - a fleet that includes trucks it cannot
     * have would make it report "no vehicle available" for orders that in fact had no chance.
     */
    private List<VehicleCapacityReference> loadFreeVehicles(CompanyScope scope, PlanningRun run) {
        return vehicleLookupPort.findAssignableInCompany(scope.companyId()).stream()
                .filter(vehicle -> !tripRepository.existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNotAndIdNot(
                        scope.companyId(), vehicle.id(), run.planningDate(), TripStatus.CANCELLED,
                        UUID.randomUUID()))
                .toList();
    }

    private PlanningRun requireDraftRun(CompanyScope scope, UUID runId) {
        PlanningRun run = planningRunRepository.findByIdAndCompanyId(runId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Planning run not found."));
        if (!run.isDraft()) {
            throw new ConflictException("Only a draft planning run can be planned automatically.");
        }
        return run;
    }
}
