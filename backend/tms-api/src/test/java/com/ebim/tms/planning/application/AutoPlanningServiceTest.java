package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.AutoPlanView.ProposedTripView;
import com.ebim.tms.planning.application.AutoPlanView.UnplannedOrderView;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.ServiceCalendarPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Automatic planning as an orchestration, which is a different subject from the heuristic it
 * calls. {@link HeuristicPlanningEngineTest} proves the arithmetic of the proposal; this suite
 * proves what happens between the proposal and the trips that end up existing - and everything
 * interesting in this class lives in that gap.
 *
 * <p>The engine is the real one. Only the boundaries are doubled: the ports that would read the
 * database, and {@code TripService}, which is replaced by a small in-memory board that can be told
 * to refuse a named order. Refusal is the whole point - between the snapshot and the write another
 * planner can take an order, and how that is reported is the difference between a planner who
 * knows what happened and one who has been told a plausible fiction.
 *
 * <p>Every case here runs with no database and no Spring context, which is deliberate: on a host
 * where Testcontainers cannot start, this is the only proof that automatic planning works at all.
 */
class AutoPlanningServiceTest {

    private static final UUID COMPANY = id("company");
    private static final UUID ORIGIN = id("origin");
    private static final UUID RUN = id("run");
    private static final UUID ACTOR = id("actor");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);
    private static final long RUN_VERSION = 4L;

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            id("organization"), "ORG", "Organization", Set.of());

    private PlanningRunRepository planningRunRepository;
    private TripRepository tripRepository;
    private OrderPlanningPort orderPlanningPort;
    private VehicleLookupPort vehicleLookupPort;
    private RouteTemplateLookupPort routeTemplateLookupPort;
    private ServiceCalendarPort serviceCalendarPort;
    private AuditRecorder auditRecorder;
    private TripBoard board;
    private AutoPlanningService service;

    /** Orders the day starts with; the snapshot is built from this list. */
    private final List<PlannableOrder> backlog = new ArrayList<>();
    /** Vehicles fleet would offer, before the double-booking filter. */
    private final List<VehicleCapacityReference> fleet = new ArrayList<>();
    /** Vehicles already committed elsewhere on this date. */
    private final Set<UUID> bookedElsewhere = new HashSet<>();
    /** Destinations the service calendar does not cover on this date. */
    private final Set<UUID> unserviceable = new HashSet<>();

    @BeforeEach
    void setUp() {
        // Cleared as well as rebuilt, because one test re-runs this method mid-test to plan the
        // same day twice (Properties.deterministic) and a backlog that accumulated would be a
        // different day, not the same one.
        backlog.clear();
        fleet.clear();
        bookedElsewhere.clear();
        unserviceable.clear();

        planningRunRepository = mock(PlanningRunRepository.class);
        tripRepository = mock(TripRepository.class);
        orderPlanningPort = mock(OrderPlanningPort.class);
        vehicleLookupPort = mock(VehicleLookupPort.class);
        routeTemplateLookupPort = mock(RouteTemplateLookupPort.class);
        serviceCalendarPort = mock(ServiceCalendarPort.class);
        auditRecorder = mock(AuditRecorder.class);
        board = new TripBoard();

        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        PlanningRun run = mock(PlanningRun.class);
        when(run.id()).thenReturn(RUN);
        when(run.originId()).thenReturn(ORIGIN);
        when(run.planningDate()).thenReturn(DATE);
        when(run.version()).thenReturn(RUN_VERSION);
        when(run.isDraft()).thenReturn(true);
        when(planningRunRepository.findByIdAndCompanyId(RUN, COMPANY)).thenReturn(Optional.of(run));

        when(orderPlanningPort.searchAssignable(any(), any()))
                .thenAnswer(invocation -> new PageResponse<>(List.copyOf(backlog), 0, 200, backlog.size()));
        when(vehicleLookupPort.findAssignableInCompany(COMPANY)).thenAnswer(invocation -> List.copyOf(fleet));
        when(tripRepository.existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNot(
                eq(COMPANY), any(), eq(DATE), eq(TripStatus.CANCELLED)))
                .thenAnswer(invocation -> bookedElsewhere.contains(invocation.getArgument(1, UUID.class)));
        when(routeTemplateLookupPort.findActiveByOriginInCompany(ORIGIN, COMPANY)).thenReturn(List.of());
        when(serviceCalendarPort.serviceableOn(any(), eq(DATE), eq(COMPANY)))
                .thenAnswer(invocation -> {
                    Set<UUID> asked = invocation.getArgument(0);
                    return asked.stream()
                            .filter(destination -> !unserviceable.contains(destination))
                            .collect(Collectors.toSet());
                });

        // The registry, not a single engine: the default is still HEURISTIC_V1, so every
        // assertion in this class goes on describing exactly the behaviour it always described.
        RoutingPort routingPort = mock(RoutingPort.class);
        when(routingPort.matrix(any(), any(), any())).thenReturn(java.util.Map.of());
        DestinationLookupPort destinationLookupPort = mock(DestinationLookupPort.class);
        when(destinationLookupPort.findAllInCompany(any(), any())).thenReturn(java.util.Map.of());
        OriginLookupPort originLookupPort = mock(OriginLookupPort.class);
        when(originLookupPort.findAllInCompany(any(), any())).thenReturn(java.util.Map.of());

        service = new AutoPlanningService(planningRunRepository, tripRepository, board.asTripService(),
                new PlanningEngines(List.of(new HeuristicPlanningEngine(), new PlanningEngineV2())),
                routingPort, destinationLookupPort, originLookupPort,
                orderPlanningPort, vehicleLookupPort, routeTemplateLookupPort,
                serviceCalendarPort, actors, auditRecorder);
    }

    // --- fixtures ---------------------------------------------------------------------------

    /** A stable id for a label, so a failing assertion names the same order on every run. */
    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    /** An order the engine can read, added to the day's backlog. Weight is the binding dimension. */
    private PlannableOrder order(String suffix, double weightKg) {
        return order(suffix, id("destination"), weightKg);
    }

    private PlannableOrder order(String suffix, UUID destination, double weightKg) {
        PlannableOrder order = new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, destination, null, null,
                DATE, "NORMAL", null, null, BigDecimal.valueOf(weightKg), BigDecimal.ONE, BigDecimal.ONE,
                null, null, OrderAmounts.NONE);
        backlog.add(order);
        return order;
    }

    /** A vehicle fleet would offer for this date. */
    private VehicleCapacityReference vehicle(String suffix, double maxWeightKg) {
        VehicleCapacityReference vehicle = new VehicleCapacityReference(id(suffix), "VEH-" + suffix,
                "PLT-" + suffix, null, null, id("type"), "TYPE", BigDecimal.valueOf(maxWeightKg),
                BigDecimal.valueOf(1_000), 1_000, true, "AVAILABLE");
        fleet.add(vehicle);
        return vehicle;
    }

    private AutoPlanView apply() {
        return service.apply(SCOPE, RUN, new PlanningActionRequest(RUN_VERSION, null));
    }

    // --- reading the result ------------------------------------------------------------------

    /** The orders the applied view says are on trips, by order number. */
    private static List<String> plannedNumbers(AutoPlanView view) {
        return view.proposed().stream().flatMap(trip -> trip.orderNumbers().stream()).sorted().toList();
    }

    /** The orders the applied view says are not, by order number. */
    private static List<String> unplannedNumbers(AutoPlanView view) {
        return view.unplanned().stream().map(UnplannedOrderView::orderNumber).sorted().toList();
    }

    private static Map<String, String> reasonsByOrder(AutoPlanView view) {
        return view.unplanned().stream()
                .collect(Collectors.toMap(UnplannedOrderView::orderNumber, UnplannedOrderView::reason));
    }

    /** The orders that are actually on the written trips, read back from the board itself. */
    private List<String> ordersOnWrittenTrips() {
        return board.liveAssignments().stream()
                .map(orderId -> backlog.stream().filter(o -> o.id().equals(orderId)).findFirst().orElseThrow())
                .map(PlannableOrder::orderNumber)
                .sorted()
                .toList();
    }

    /**
     * The invariant the whole feature rests on, checked against the view a planner reads: every
     * order that went into the day comes back exactly once, on a trip or in the unplanned list,
     * and the two lists never name the same order.
     */
    private void assertInputInvariant(AutoPlanView view) {
        List<String> planned = plannedNumbers(view);
        List<String> unplanned = unplannedNumbers(view);
        List<String> input = backlog.stream().map(PlannableOrder::orderNumber).sorted().toList();

        assertThat(planned).doesNotHaveDuplicates();
        assertThat(unplanned).doesNotHaveDuplicates();
        if (!unplanned.isEmpty()) {
            assertThat(new HashSet<>(planned)).doesNotContainAnyElementsOf(unplanned);
        }

        List<String> accountedFor = new ArrayList<>(planned);
        accountedFor.addAll(unplanned);
        assertThat(accountedFor.stream().sorted().toList()).isEqualTo(input);
        assertThat(view.ordersConsidered()).isEqualTo(input.size());

        // And what the view calls planned is what the board actually holds - the claim the old
        // code could not make, because it reported the proposal rather than the writes.
        assertThat(planned).isEqualTo(ordersOnWrittenTrips());
    }

    // --- the happy path ----------------------------------------------------------------------

    @Nested
    @DisplayName("writing a proposal that is still valid")
    class Accepted {

        @Test
        @DisplayName("a proposed order that TripService accepts is planned, and says so")
        void singleOrderAccepted() {
            order("a", 1_000);
            vehicle("truck", 10_000);

            AutoPlanView view = apply();

            assertThat(view.applied()).isTrue();
            assertThat(view.created()).hasSize(1);
            assertThat(plannedNumbers(view)).containsExactly("TO-a");
            assertThat(view.unplanned()).isEmpty();
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("several proposed trips are all written, each with its own vehicle")
        void multipleProposedTrips() {
            order("a", 9_000);
            order("b", 9_000);
            order("c", 9_000);
            vehicle("t1", 10_000);
            vehicle("t2", 10_000);
            vehicle("t3", 10_000);

            AutoPlanView view = apply();

            assertThat(view.created()).hasSize(3);
            assertThat(view.proposed()).extracting(ProposedTripView::vehicleId).doesNotHaveDuplicates();
            assertThat(plannedNumbers(view)).containsExactly("TO-a", "TO-b", "TO-c");
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("the run is audited once, with what it actually did")
        void auditsTheRun() {
            order("a", 1_000);
            vehicle("truck", 10_000);

            apply();

            verify(auditRecorder).record(eq(SCOPE), eq(AuditAggregateType.PLANNING_RUN), eq(RUN),
                    eq(AuditAction.AUTO_PLAN), eq(Map.of("engine", "HEURISTIC_V1",
                            "tripsCreated", "1", "ordersConsidered", "1")));
        }

        @Test
        @DisplayName("nothing is ever confirmed: the trips are left as drafts for a planner")
        void neverConfirms() {
            order("a", 1_000);
            vehicle("truck", 10_000);

            apply();

            assertThat(board.confirmed()).isEmpty();
        }
    }

    // --- what the engine itself cannot place ---------------------------------------------------

    @Nested
    @DisplayName("what the day cannot absorb")
    class NotPlannable {

        @Test
        @DisplayName("no free vehicle: every order is reported, and nothing is written")
        void noFreeVehicles() {
            order("a", 1_000);
            order("b", 1_000);

            AutoPlanView view = apply();

            assertThat(view.created()).isEmpty();
            assertThat(board.created()).isEmpty();
            assertThat(view.vehiclesOffered()).isZero();
            assertThat(reasonsByOrder(view)).containsOnlyKeys("TO-a", "TO-b")
                    .containsValue(UnplannedReason.NO_FLEET.name());
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("a vehicle already booked that date is not offered to the engine")
        void bookedVehiclesAreNotOffered() {
            order("a", 1_000);
            VehicleCapacityReference busy = vehicle("busy", 10_000);
            bookedElsewhere.add(busy.id());

            AutoPlanView view = apply();

            assertThat(view.vehiclesOffered()).isZero();
            assertThat(board.created()).isEmpty();
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("an order larger than any vehicle offered is reported as needing a split")
        void orderExceedsLargestVehicle() {
            order("small", 1_000);
            order("huge", 30_000);
            vehicle("truck", 10_000);

            AutoPlanView view = apply();

            assertThat(plannedNumbers(view)).containsExactly("TO-small");
            assertThat(reasonsByOrder(view))
                    .containsExactly(Map.entry("TO-huge", UnplannedReason.EXCEEDS_LARGEST_VEHICLE.name()));
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("a destination the calendar does not serve is excluded before the engine sees it")
        void destinationNotServiceable() {
            UUID closed = id("closed-destination");
            order("a", id("destination"), 1_000);
            order("b", closed, 1_000);
            unserviceable.add(closed);
            vehicle("truck", 10_000);

            AutoPlanView view = apply();

            assertThat(plannedNumbers(view)).containsExactly("TO-a");
            assertThat(reasonsByOrder(view))
                    .containsExactly(Map.entry("TO-b", UnplannedReason.NOT_SERVICEABLE_ON_DATE.name()));
            assertInputInvariant(view);
        }
    }

    // --- the race that makes this class hard ----------------------------------------------------

    @Nested
    @DisplayName("losing a race between the snapshot and the write")
    class LostRace {

        @Test
        @DisplayName("an order taken by somebody else is reported unplanned, not planned")
        void singleOrderRejected() {
            order("a", 1_000);
            vehicle("truck", 10_000);
            board.refuse("TO-a", new InvalidRequestException(
                    "orderId does not reference an order that is ready for planning in this company."));

            AutoPlanView view = apply();

            assertThat(plannedNumbers(view)).isEmpty();
            assertThat(reasonsByOrder(view))
                    .containsExactly(Map.entry("TO-a", UnplannedReason.TAKEN_WHILE_PLANNING.name()));
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("a trip that keeps some of its load keeps exactly what it got")
        void mixedAcceptedAndRejected() {
            order("a", 1_000);
            order("b", 1_000);
            order("c", 1_000);
            vehicle("truck", 10_000);
            board.refuse("TO-b", new ConflictException("This order is already assigned to another trip."));

            AutoPlanView view = apply();

            assertThat(view.created()).hasSize(1);
            assertThat(plannedNumbers(view)).containsExactly("TO-a", "TO-c");
            assertThat(reasonsByOrder(view))
                    .containsExactly(Map.entry("TO-b", UnplannedReason.TAKEN_WHILE_PLANNING.name()));
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("an order refused after the write is never reported as planned as well")
        void neverBothPlannedAndUnplanned() {
            order("a", 1_000);
            order("b", 1_000);
            vehicle("truck", 10_000);
            board.refuse("TO-b", new ConflictException("Taken."));

            AutoPlanView view = apply();

            // The regression this test exists for: the applied view used to be built from the
            // proposal, so TO-b appeared on its proposed trip *and* in the unplanned list.
            assertThat(plannedNumbers(view)).doesNotContain("TO-b");
            assertThat(unplannedNumbers(view)).containsExactly("TO-b");
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("a trip that keeps nothing is cancelled rather than left holding a vehicle")
        void allOrdersRejectedLeavesNoEmptyTrip() {
            order("a", 1_000);
            order("b", 1_000);
            vehicle("truck", 10_000);
            board.refuse("TO-a", new ConflictException("Taken."));
            board.refuse("TO-b", new ConflictException("Taken."));

            AutoPlanView view = apply();

            assertThat(board.created()).hasSize(1);
            assertThat(board.liveTrips()).isEmpty();
            assertThat(board.cancelled()).containsExactlyElementsOf(board.created());
            assertThat(view.created()).isEmpty();
            assertThat(view.proposed()).isEmpty();
            assertThat(unplannedNumbers(view)).containsExactly("TO-a", "TO-b");
            assertInputInvariant(view);
        }

        @Test
        @DisplayName("the vehicle of a cancelled empty trip is free for the next run")
        void cancelledEmptyTripStrandsNoVehicle() {
            order("a", 1_000);
            VehicleCapacityReference truck = vehicle("truck", 10_000);
            board.refuse("TO-a", new ConflictException("Taken."));

            apply();

            // A cancelled trip is exactly what loadFreeVehicles filters on: `status <> CANCELLED`.
            // If the empty trip had survived, this vehicle would be reported busy all day by a
            // trip that carries nothing.
            assertThat(board.vehiclesHeldByLiveTrips()).doesNotContain(truck.id());
        }

        @Test
        @DisplayName("one trip losing everything does not cost another trip its load")
        void oneEmptiedTripDoesNotAffectTheOthers() {
            order("a", 9_000);
            order("b", 9_000);
            vehicle("t1", 10_000);
            vehicle("t2", 10_000);
            board.refuse("TO-a", new ConflictException("Taken."));

            AutoPlanView view = apply();

            assertThat(board.created()).hasSize(2);
            assertThat(board.liveTrips()).hasSize(1);
            assertThat(view.created()).hasSize(1);
            assertThat(plannedNumbers(view)).containsExactly("TO-b");
            assertInputInvariant(view);
        }
    }

    // --- properties that must hold whatever happened -----------------------------------------

    @Nested
    @DisplayName("properties of an applied plan")
    class Properties {

        @Test
        @DisplayName("the same snapshot and the same refusals produce the same plan")
        void deterministic() {
            order("a", 4_000);
            order("b", 4_000);
            order("c", 4_000);
            vehicle("t1", 10_000);
            vehicle("t2", 10_000);
            board.refuse("TO-b", new ConflictException("Taken."));

            String first = summarise(apply());

            setUpAgainWithTheSameDay();
            String second = summarise(apply());

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("a preview writes nothing at all")
        void previewWritesNothing() {
            order("a", 1_000);
            vehicle("truck", 10_000);

            AutoPlanView view = service.preview(SCOPE, RUN, null);

            assertThat(view.applied()).isFalse();
            assertThat(view.created()).isEmpty();
            assertThat(board.created()).isEmpty();
            verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a run that is not a draft is refused before anything is read")
        void refusesANonDraftRun() {
            PlanningRun confirmed = mock(PlanningRun.class);
            when(confirmed.isDraft()).thenReturn(false);
            when(planningRunRepository.findByIdAndCompanyId(RUN, COMPANY)).thenReturn(Optional.of(confirmed));

            assertThatExceptionOfType(ConflictException.class).isThrownBy(AutoPlanningServiceTest.this::apply);
            assertThat(board.created()).isEmpty();
        }

        @Test
        @DisplayName("a stale run version is refused rather than planned over")
        void refusesAStaleVersion() {
            order("a", 1_000);
            vehicle("truck", 10_000);

            assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                    service.apply(SCOPE, RUN, new PlanningActionRequest(RUN_VERSION - 1, null)));
            assertThat(board.created()).isEmpty();
        }

        @Test
        @DisplayName("a run in another company is not found")
        void refusesAnotherCompanysRun() {
            CompanyScope other = new CompanyScope(id("other-company"), "CO-B", "Company B", "America/Lima",
                    id("organization"), "ORG", "Organization", Set.of());

            assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() ->
                    service.apply(other, RUN, new PlanningActionRequest(RUN_VERSION, null)));
        }

        /** A comparable rendering of a plan: which orders on which vehicle, and what was left. */
        private String summarise(AutoPlanView view) {
            String trips = view.proposed().stream()
                    .map(trip -> trip.vehicleCode() + "=" + String.join("+", trip.orderNumbers()))
                    .sorted()
                    .collect(Collectors.joining(","));
            String left = view.unplanned().stream()
                    .map(unplanned -> unplanned.orderNumber() + ":" + unplanned.reason())
                    .sorted()
                    .collect(Collectors.joining(","));
            return trips + "|" + left;
        }

        /** Rebuilds the collaborators around the backlog and fleet the test already set up. */
        private void setUpAgainWithTheSameDay() {
            List<PlannableOrder> orders = List.copyOf(backlog);
            List<VehicleCapacityReference> vehicles = List.copyOf(fleet);
            Set<UUID> refusals = board.refusedOrderIds();
            setUp();
            backlog.addAll(orders);
            fleet.addAll(vehicles);
            orders.stream()
                    .filter(order -> refusals.contains(order.id()))
                    .forEach(order -> board.refuse(order.orderNumber(), new ConflictException("Taken.")));
        }
    }

    // --- the double that stands in for TripService ---------------------------------------------

    /**
     * An in-memory stand-in for {@code TripService}: enough of a trip board to answer what
     * automatic planning asks it, and no more. It is a hand-written double rather than a mock
     * because these tests are about a sequence of writes and what survives it - which is state,
     * and mock stubbing describes state badly.
     *
     * <p>It enforces the two rules that make the assertions meaningful: an order cannot be
     * assigned twice, and a cancelled trip stops holding its vehicle.
     */
    private final class TripBoard {

        private final Map<UUID, List<UUID>> ordersByTrip = new LinkedHashMap<>();
        private final Map<UUID, UUID> vehicleByTrip = new LinkedHashMap<>();
        private final Map<UUID, Long> versionByTrip = new HashMap<>();
        private final Set<UUID> cancelledTrips = new LinkedHashSet<>();
        private final Map<UUID, RuntimeException> refusals = new LinkedHashMap<>();
        private int tripCount;

        /** Tell the board to refuse this order the way a lost race would. */
        void refuse(String orderNumber, RuntimeException reason) {
            refusals.put(orderIdOf(orderNumber), reason);
        }

        Set<UUID> refusedOrderIds() {
            return Set.copyOf(refusals.keySet());
        }

        List<UUID> created() {
            return List.copyOf(ordersByTrip.keySet());
        }

        List<UUID> cancelled() {
            return List.copyOf(cancelledTrips);
        }

        /** Trips that still exist as drafts - the ones a planner would find on the board. */
        List<UUID> liveTrips() {
            return ordersByTrip.keySet().stream().filter(trip -> !cancelledTrips.contains(trip)).toList();
        }

        List<UUID> liveAssignments() {
            return liveTrips().stream().flatMap(trip -> ordersByTrip.get(trip).stream()).toList();
        }

        Set<UUID> vehiclesHeldByLiveTrips() {
            return liveTrips().stream().map(vehicleByTrip::get).collect(Collectors.toSet());
        }

        /** Nothing here ever confirms; the assertion that it does not needs somewhere to look. */
        List<UUID> confirmed() {
            return List.of();
        }

        private UUID orderIdOf(String orderNumber) {
            return backlog.stream()
                    .filter(order -> order.orderNumber().equals(orderNumber))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no such order in the backlog: " + orderNumber))
                    .id();
        }

        TripService asTripService() {
            TripService tripService = mock(TripService.class);
            when(tripService.create(any(), any(), any())).thenAnswer(invocation -> {
                TripCreateRequest request = invocation.getArgument(2);
                UUID tripId = id("trip-" + tripCount++);
                ordersByTrip.put(tripId, new ArrayList<>());
                vehicleByTrip.put(tripId, request.vehicleId());
                versionByTrip.put(tripId, 0L);
                return detailOf(tripId);
            });
            when(tripService.assignOrder(any(), any(), any())).thenAnswer(invocation -> {
                UUID tripId = invocation.getArgument(1);
                UUID orderId = invocation.getArgument(2, AssignOrderRequest.class).orderId();
                RuntimeException refusal = refusals.get(orderId);
                if (refusal != null) {
                    throw refusal;
                }
                if (liveAssignments().contains(orderId)) {
                    throw new ConflictException("This order is already assigned to another trip.");
                }
                ordersByTrip.get(tripId).add(orderId);
                versionByTrip.merge(tripId, 1L, Long::sum);
                return detailOf(tripId);
            });
            when(tripService.cancel(any(), any(), any())).thenAnswer(invocation -> {
                UUID tripId = invocation.getArgument(1);
                cancelledTrips.add(tripId);
                versionByTrip.merge(tripId, 1L, Long::sum);
                return detailOf(tripId);
            });
            return tripService;
        }

        /**
         * The trip as {@code AutoPlanningService} reads it: its id, its version, and its stops.
         * Everything the service does not look at is left null on purpose - a fixture that filled
         * it in would be asserting the assembler's job from the wrong test.
         */
        private TripDetailView detailOf(UUID tripId) {
            List<UUID> orders = ordersByTrip.get(tripId);
            List<UUID> destinations = orders.stream()
                    .map(orderId -> backlog.stream()
                            .filter(order -> order.id().equals(orderId))
                            .findFirst()
                            .orElseThrow()
                            .destinationId())
                    .distinct()
                    .sorted(Comparator.comparing(UUID::toString))
                    .toList();

            List<TripStopView> stops = new ArrayList<>();
            for (int index = 0; index < destinations.size(); index++) {
                stops.add(new TripStopView(id("stop-" + tripId + "-" + index), index + 1, destinations.get(index),
                        null, null, null, null, null, null, null, 1L, null, Set.of(), null, null, null,
                        null, null, 0));
            }

            TripView trip = new TripView(tripId, COMPANY, RUN, null, DATE, 1, "SH-" + tripId,
                    cancelledTrips.contains(tripId) ? TripStatus.CANCELLED : TripStatus.DRAFT,
                    ORIGIN, null, null, null, null, vehicleByTrip.get(tripId), null, null, null, null, null,
                    // V42: acceptedCarrierId, acceptedCarrierName, awaitsCarrierVehicle - no
                    // subcontracting in this fixture, which is what false says.
                    null, null, false,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, Set.of(), null, stops.size(), orders.size(), versionByTrip.get(tripId),
                    null, null);
            return new TripDetailView(trip, List.of(), stops, List.of(), List.of(), TripRouteMetrics.NONE);
        }
    }
}
