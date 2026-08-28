package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.DeliveryEvidence;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.TransportEvent;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.DeliveryEvidenceRepository;
import com.ebim.tms.planning.infrastructure.OrderDeliveryRepository;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Builds the read models both planning services return, and is the single place that decides
 * <em>which</em> capacity a trip is reporting (live from its vehicle while draft, the frozen
 * snapshot once confirmed).
 *
 * <p>It is also the single place the shipment header is assembled
 * ({@code docs/domain/SHIPMENT_V2.md}): a trip stores only what cannot be derived, and everything
 * else on {@link TripView} - plan number, planning date, origin, carrier name, vehicle type,
 * route - is resolved here from the module that owns it.
 *
 * <p>Everything is batched. A board of N trips costs a fixed number of queries - one grouped
 * load, one grouped stop count, and the six lookups behind {@link ShipmentReferences} - never N
 * of anything. A trip detail adds one assignment query and one batched order lookup, and touches
 * no order line at all.
 */
@Service
public class TripViewAssembler {

    private final PlanningRunRepository planningRunRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final TripStopRepository tripStopRepository;
    private final TripExceptionRepository tripExceptionRepository;
    private final OrderDeliveryRepository orderDeliveryRepository;
    private final TripRoutingService tripRoutingService;
    private final DeliveryEvidenceRepository evidenceRepository;
    private final OriginLookupPort originLookupPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final CarrierLookupPort carrierLookupPort;
    private final DriverLookupPort driverLookupPort;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final OrderPlanningPort orderPlanningPort;
    private final PlanningCapacityService capacityService;

    public TripViewAssembler(PlanningRunRepository planningRunRepository,
            TripOrderAssignmentRepository assignmentRepository, TripStopRepository tripStopRepository,
            TripExceptionRepository tripExceptionRepository, OrderDeliveryRepository orderDeliveryRepository,
            DeliveryEvidenceRepository evidenceRepository,
            OriginLookupPort originLookupPort, VehicleLookupPort vehicleLookupPort,
            CarrierLookupPort carrierLookupPort, DriverLookupPort driverLookupPort,
            RouteTemplateLookupPort routeTemplateLookupPort, DestinationLookupPort destinationLookupPort,
            OrderPlanningPort orderPlanningPort, PlanningCapacityService capacityService,
            TripRoutingService tripRoutingService) {
        this.planningRunRepository = planningRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripExceptionRepository = tripExceptionRepository;
        this.orderDeliveryRepository = orderDeliveryRepository;
        this.tripRoutingService = tripRoutingService;
        this.evidenceRepository = evidenceRepository;
        this.originLookupPort = originLookupPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.carrierLookupPort = carrierLookupPort;
        this.driverLookupPort = driverLookupPort;
        this.routeTemplateLookupPort = routeTemplateLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.orderPlanningPort = orderPlanningPort;
        this.capacityService = capacityService;
    }

    /** The board rows of a whole planning run, in a fixed number of queries. */
    public List<TripView> toViews(List<Trip> trips, UUID companyId) {
        if (trips.isEmpty()) {
            return List.of();
        }
        ShipmentReferences references = referencesOf(trips, companyId);
        Map<UUID, CapacityLoad> loads = loadsOf(trips);
        // Counted in one grouped query rather than read from trip.stops(): that collection is
        // lazy, so asking a board of 300 trips how many stops each has would issue 300 selects.
        Map<UUID, Long> stopCounts = stopCountsOf(trips);
        return trips.stream()
                .map(trip -> toView(trip, loads.getOrDefault(trip.id(), CapacityLoad.EMPTY), references,
                        stopCounts.getOrDefault(trip.id(), 0L)))
                .toList();
    }

    /**
     * One trip with its active assignments, ordered stops, the problems raised against it and what
     * was delivered at each stop.
     */
    public TripDetailView toDetail(Trip trip, UUID companyId) {
        List<TripException> exceptions =
                tripExceptionRepository.findByCompanyIdAndTripIdOrderByReportedAtDesc(companyId, trip.id());
        List<TripOrderAssignment> assignments =
                assignmentRepository.findByTripIdAndStatusOrderByAssignedAtAsc(trip.id(), AssignmentStatus.ACTIVE);
        List<OrderDelivery> deliveries = orderDeliveryRepository.findByCompanyIdAndTripId(companyId, trip.id());

        // The union of the two, in one batched lookup: a delivery can name an order that has since
        // been taken off the trip - the goods were still handed over - and resolving its number
        // from the assignment list alone would print a blank row for exactly the case somebody is
        // investigating.
        Set<UUID> orderIds = assignments.stream().map(TripOrderAssignment::orderId).collect(Collectors.toSet());
        deliveries.forEach(delivery -> orderIds.add(delivery.orderId()));
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(orderIds, companyId);

        Set<UUID> destinationIds =
                new HashSet<>(orders.values().stream().map(PlannableOrder::destinationId).toList());
        trip.stops().forEach(stop -> destinationIds.add(stop.destinationId()));
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(destinationIds, companyId);

        CapacityLoad load = CapacityLoad.of(
                assignmentRepository.loadByTripId(trip.id(), AssignmentStatus.ACTIVE));
        // One trip: its stops are already being rendered below, so counting them in memory
        // costs nothing extra - unlike the board above, this is not an N+1.
        ShipmentReferences references = referencesOf(List.of(trip), companyId);
        TripView view = toView(trip, load, references, trip.stops().size());

        List<TripAssignmentView> assignmentViews = assignments.stream()
                .map(assignment -> toAssignmentView(assignment, orders.get(assignment.orderId()), destinations))
                .toList();
        // Counted from the *assignments* and not from the resolved order map, which since V28 also
        // holds orders that were delivered and later taken off the trip: a stop's order count is
        // what it is carrying now, not everything it has ever carried.
        Map<UUID, Long> ordersPerDestination = assignments.stream()
                .map(assignment -> orders.get(assignment.orderId()))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(PlannableOrder::destinationId, Collectors.counting()));
        // Counted from the list already in hand rather than with a second grouped query: a trip
        // has a handful of exceptions, and they were all just loaded.
        Map<UUID, Long> openExceptionsPerStop = exceptions.stream()
                .filter(TripException::isOpen)
                .filter(exception -> exception.tripStopId() != null)
                .collect(Collectors.groupingBy(TripException::tripStopId, Collectors.counting()));
        List<TripStopView> stopViews = trip.stops().stream()
                .map(stop -> toStopView(stop, destinations.get(stop.destinationId()),
                        ordersPerDestination.getOrDefault(stop.destinationId(), 0L), trip.status(),
                        openExceptionsPerStop.getOrDefault(stop.id(), 0L)))
                .toList();
        Map<UUID, TripStop> stopsById = stopsById(trip);
        List<TripExceptionView> exceptionViews = exceptions.stream()
                .map(exception -> toExceptionView(exception, stopsById, destinations))
                .toList();

        // The run's distance and drive time (V38), measured over references this method has
        // already loaded - so the first consumer of RoutingPort costs the detail view no extra
        // master-data query, only the cache reads the port itself makes.
        TripRouteMetrics routing = tripRoutingService.measure(
                trip, originOf(trip, references), destinations);

        return new TripDetailView(view, assignmentViews, stopViews, exceptionViews,
                toDeliveryViews(deliveries, stopsById, orders, companyId), routing);
    }

    /**
     * The trip's deliveries with their evidence attached, in visiting order.
     *
     * <p>One query for every artefact of every delivery rather than one per delivery - a trip with
     * thirty orders and a photo each would otherwise cost thirty round trips to render one screen.
     * Skipped entirely when nothing has been recorded yet, which is every trip that has not left.
     */
    private List<OrderDeliveryView> toDeliveryViews(List<OrderDelivery> deliveries, Map<UUID, TripStop> stopsById,
            Map<UUID, PlannableOrder> orders, UUID companyId) {
        if (deliveries.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DeliveryEvidence>> evidence = evidenceRepository
                .findByCompanyIdAndOrderDeliveryIdInOrderByUploadedAtAsc(companyId,
                        deliveries.stream().map(OrderDelivery::id).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.groupingBy(DeliveryEvidence::orderDeliveryId));

        return deliveries.stream()
                // Visiting order, then recording order within a stop: the sequence a dispatcher
                // reads them in. A delivery whose stop has somehow gone sorts last rather than
                // throwing - the row is still the record of a handover.
                .sorted(Comparator
                        .comparingInt((OrderDelivery delivery) -> sequenceOf(stopsById, delivery))
                        .thenComparing(OrderDelivery::recordedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(delivery -> {
                    TripStop stop = stopsById.get(delivery.tripStopId());
                    PlannableOrder order = orders.get(delivery.orderId());
                    return new OrderDeliveryView(delivery.id(), delivery.tripStopId(),
                            stop == null ? null : stop.sequence(), delivery.orderId(),
                            order == null ? null : order.orderNumber(), delivery.result(), delivery.deliveredAt(),
                            delivery.receiverName(), delivery.receiverDocument(), delivery.notes(),
                            delivery.source(), delivery.actorDisplayName(), delivery.recordedAt(),
                            evidence.getOrDefault(delivery.id(), List.of()).stream()
                                    .map(TripViewAssembler::toEvidenceView)
                                    .toList());
                })
                .toList();
    }

    private static int sequenceOf(Map<UUID, TripStop> stopsById, OrderDelivery delivery) {
        TripStop stop = stopsById.get(delivery.tripStopId());
        return stop == null ? Integer.MAX_VALUE : stop.sequence();
    }

    /** Metadata only: no bytes, no URL, no storage key - see {@link DeliveryEvidenceView}. */
    private static DeliveryEvidenceView toEvidenceView(DeliveryEvidence evidence) {
        return new DeliveryEvidenceView(evidence.id(), evidence.evidenceType(), evidence.contentType(),
                evidence.sizeBytes(), evidence.checksumSha256(), evidence.originalFilename(),
                evidence.capturedAt(), evidence.uploadedAt());
    }

    /**
     * One trip's timeline, with each entry's stop resolved for display.
     *
     * <p>Takes the events rather than loading them, so the caller decides what it is showing - the
     * whole day, or the tail of it - and this stays a pure assembly step. The destinations behind
     * the stops are read once for the trip, not once per event.
     */
    public List<TransportEventView> toEventViews(Trip trip, List<TransportEvent> events, UUID companyId) {
        if (events.isEmpty()) {
            return List.of();
        }
        Map<UUID, TripStop> stops = stopsById(trip);
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(
                stops.values().stream().map(TripStop::destinationId).collect(Collectors.toSet()), companyId);
        return events.stream().map(event -> {
            TripStop stop = event.tripStopId() == null ? null : stops.get(event.tripStopId());
            MasterReference destination = stop == null ? null : destinations.get(stop.destinationId());
            return new TransportEventView(event.id(), event.tripId(), event.tripStopId(),
                    stop == null ? null : stop.sequence(),
                    destination == null ? null : destination.code(),
                    destination == null ? null : destination.name(),
                    event.eventType(), event.eventTime(), event.recordedAt(), event.source(),
                    event.actorDisplayName(), event.notes(), event.metadata());
        }).toList();
    }

    /**
     * The trip's stops indexed for the event and exception views, which point at one by id.
     *
     * <p>Stops with no id are filtered out rather than allowed to reach {@code toMap}, which throws
     * on a null key: a stop is only id-less before its first flush, and nothing can be pointing at
     * it yet in that window - so the honest result is "not found", not an exception.
     */
    private static Map<UUID, TripStop> stopsById(Trip trip) {
        return trip.stops().stream()
                .filter(stop -> stop.id() != null)
                .collect(Collectors.toMap(TripStop::id, stop -> stop));
    }

    /**
     * The capacity answer for a whole set of trips at once, indexed by trip id - two queries for
     * the set, never one per trip.
     *
     * <p>Exists for the control tower's workload panel, which has to rank the day's shipments by
     * how full they are before it knows which five it will actually show. Ranking through
     * {@link #toViews} would resolve six masters for four hundred trips to display five of them;
     * this resolves only what the ranking reads - the vehicles' limits and the assigned load - and
     * the caller builds full views for the handful that win.
     *
     * <p>Goes through {@link #summarize} like every other caller, so the "snapshot once confirmed,
     * live while draft" rule is still decided in exactly one place.
     */
    public Map<UUID, TripCapacityView> capacitiesOf(List<Trip> trips, UUID companyId) {
        if (trips.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VehicleCapacityReference> vehicles = vehiclesOf(trips, companyId);
        Map<UUID, CapacityLoad> loads = loadsOf(trips);
        Map<UUID, TripCapacityView> capacities = new HashMap<>();
        for (Trip trip : trips) {
            capacities.put(trip.id(),
                    summarize(trip, loads.getOrDefault(trip.id(), CapacityLoad.EMPTY), vehicles));
        }
        return capacities;
    }

    /** The capacity answer for one trip, on its own - the {@code GET /trips/{id}/capacity} body. */
    public TripCapacityView capacityOf(Trip trip, UUID companyId) {
        CapacityLoad load = CapacityLoad.of(assignmentRepository.loadByTripId(trip.id(), AssignmentStatus.ACTIVE));
        return summarize(trip, load, vehiclesOf(List.of(trip), companyId));
    }

    /**
     * The rule the whole capacity model rests on: a confirmed trip reads its frozen snapshot, a
     * draft trip re-reads its vehicle live, and a trip with no vehicle is unlimited. Nothing else
     * in the module is allowed to decide this - see {@code docs/domain/CAPACITY_MODEL.md}.
     *
     * <p>Asked as "does this trip carry a snapshot?" and not as "is its status CONFIRMED?" since
     * migration V25: a trip that has been dispatched, completed, or cancelled after confirmation
     * still has to report the capacity it was validated against, and four more {@code ==}
     * comparisons here would be four more places to forget the next state.
     */
    TripCapacityView summarize(Trip trip, CapacityLoad load, Map<UUID, VehicleCapacityReference> vehicles) {
        if (trip.hasCapacitySnapshot()) {
            return capacityService.summarize(trip.id(), CapacitySource.SNAPSHOT, CapacityLimits.ofSnapshot(trip), load);
        }
        VehicleCapacityReference vehicle = trip.vehicleId() == null ? null : vehicles.get(trip.vehicleId());
        if (vehicle == null) {
            return capacityService.summarize(trip.id(), CapacitySource.NONE, CapacityLimits.unlimited(), load);
        }
        return capacityService.summarize(trip.id(), CapacitySource.LIVE, CapacityLimits.of(vehicle), load);
    }

    Map<UUID, VehicleCapacityReference> vehiclesOf(List<Trip> trips, UUID companyId) {
        Set<UUID> vehicleIds = trips.stream().map(Trip::vehicleId).filter(Objects::nonNull).collect(Collectors.toSet());
        return vehicleIds.isEmpty() ? Map.of() : vehicleLookupPort.findAllInCompany(vehicleIds, companyId);
    }

    /**
     * The six batched lookups behind a shipment header, for a whole set of trips at once.
     *
     * <p>The runs come first and are read company-scoped, not by id alone: the origin of a
     * shipment is its run's origin, so resolving it means resolving the run, and a run belonging
     * to another tenant must not be loadable even though the trips that named it were already
     * scoped.
     */
    /** The run's origin, which is where the vehicle starts from. Null when the run is unresolvable. */
    private MasterReference originOf(Trip trip, ShipmentReferences references) {
        PlanningRun run = references.runs().get(trip.planningRunId());
        return run == null ? null : references.origins().get(run.originId());
    }

    private ShipmentReferences referencesOf(List<Trip> trips, UUID companyId) {
        if (trips.isEmpty()) {
            return ShipmentReferences.EMPTY;
        }
        Map<UUID, PlanningRun> runs = planningRunRepository
                .findByIdInAndCompanyId(idsOf(trips, Trip::planningRunId), companyId).stream()
                .collect(Collectors.toMap(PlanningRun::id, Function.identity()));
        Map<UUID, MasterReference> origins = originLookupPort.findAllInCompany(
                runs.values().stream().map(PlanningRun::originId).collect(Collectors.toSet()), companyId);
        Map<UUID, MasterReference> carriers =
                carrierLookupPort.findAllInCompany(
                        // Both carriers, in one lookup. A subcontracted shipment names the carrier
                        // that accepted it as well as the owner of the vehicle on it (V42), and a
                        // second query per board would be the N+1 this batching exists to avoid.
                        idsOf(trips, Trip::carrierId, Trip::acceptedCarrierId), companyId);
        // Display-grade, active or not: a driver who has left the fleet must still render on the
        // shipments they ran - the same contract CarrierLookupPort documents.
        Map<UUID, DriverReference> drivers =
                driverLookupPort.findAllInCompany(idsOf(trips, Trip::driverId), companyId);
        Map<UUID, RouteTemplate> routes =
                routeTemplateLookupPort.findAllInCompany(idsOf(trips, Trip::routeId), companyId);
        return new ShipmentReferences(runs, origins, vehiclesOf(trips, companyId), carriers, drivers, routes);
    }

    private static Set<UUID> idsOf(List<Trip> trips, Function<Trip, UUID> extractor) {
        return trips.stream().map(extractor).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** The union over several id-bearing fields, for a master a trip can name more than once (V42). */
    @SafeVarargs
    private static Set<UUID> idsOf(List<Trip> trips, Function<Trip, UUID>... extractors) {
        return trips.stream()
                .flatMap(trip -> Stream.of(extractors).map(extractor -> extractor.apply(trip)))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Map<UUID, CapacityLoad> loadsOf(List<Trip> trips) {
        Map<UUID, CapacityLoad> loads = new HashMap<>();
        List<UUID> tripIds = trips.stream().map(Trip::id).toList();
        for (TripOrderAssignmentRepository.TripLoadRow row
                : assignmentRepository.loadByTripIds(tripIds, AssignmentStatus.ACTIVE)) {
            loads.put(row.getTripId(), CapacityLoad.of(row));
        }
        return loads;
    }

    private TripView toView(Trip trip, CapacityLoad load, ShipmentReferences references, long stopCount) {
        PlanningRun run = references.runs().get(trip.planningRunId());
        MasterReference origin = run == null ? null : references.origins().get(run.originId());
        VehicleCapacityReference vehicle = trip.vehicleId() == null ? null : references.vehicles().get(trip.vehicleId());
        // Resolved from the trip's own carrier_id, not from the vehicle: see CarrierLookupPort.
        MasterReference carrier = trip.carrierId() == null ? null : references.carriers().get(trip.carrierId());
        MasterReference acceptedCarrier = trip.acceptedCarrierId() == null
                ? null : references.carriers().get(trip.acceptedCarrierId());
        DriverReference driver = trip.driverId() == null ? null : references.drivers().get(trip.driverId());
        RouteTemplate route = trip.routeId() == null ? null : references.routes().get(trip.routeId());

        return new TripView(trip.id(), trip.companyId(), trip.planningRunId(),
                run == null ? null : run.planNumber(), trip.planningDate(), trip.tripNumber(), trip.shipmentNumber(),
                trip.status(),
                run == null ? null : run.originId(), origin == null ? null : origin.code(),
                origin == null ? null : origin.name(), origin == null ? null : origin.latitude(),
                origin == null ? null : origin.longitude(),
                trip.vehicleId(), vehicle == null ? null : vehicle.code(),
                vehicle == null ? null : vehicle.licensePlate(), vehicle == null ? null : vehicle.vehicleTypeCode(),
                trip.carrierId(), carrier == null ? null : carrier.name(),
                trip.acceptedCarrierId(), acceptedCarrier == null ? null : acceptedCarrier.name(),
                trip.awaitsCarrierVehicle(),
                trip.driverId(), driver == null ? null : driver.code(), driver == null ? null : driver.fullName(),
                driver == null ? null : driver.phone(), driver == null ? null : driver.licenseNumber(),
                driver == null ? null : driver.licenseExpiresOn(),
                // Against the trip's planning date, not today: the badge answers "is this licence
                // good on the day this shipment runs" - see TripView's javadoc.
                driver == null ? null : driver.licenseStatusOn(trip.planningDate()),
                trip.routeId(), route == null ? null : route.code(), route == null ? null : route.name(),
                trip.plannedDepartureAt(), trip.readyAt(), trip.actualDepartureAt(), trip.actualCompletionAt(),
                trip.cancelledAt(), trip.cancelReason(), trip.status().allowedTransitions(),
                summarize(trip, load, references.vehicles()), (int) stopCount,
                load.orderCount(), trip.version(), trip.createdAt(), trip.updatedAt());
    }

    private Map<UUID, Long> stopCountsOf(List<Trip> trips) {
        Map<UUID, Long> counts = new HashMap<>();
        for (TripStopRepository.TripStopCount count
                : tripStopRepository.countByTripIds(trips.stream().map(Trip::id).toList())) {
            counts.put(count.getTripId(), count.getStopCount());
        }
        return counts;
    }

    private static TripAssignmentView toAssignmentView(
            TripOrderAssignment assignment, PlannableOrder order, Map<UUID, MasterReference> destinations) {
        MasterReference destination = order == null ? null : destinations.get(order.destinationId());
        return new TripAssignmentView(assignment.id(), assignment.orderId(),
                order == null ? null : order.orderNumber(), order == null ? null : order.destinationId(),
                destination == null ? null : destination.code(), destination == null ? null : destination.name(),
                order == null ? null : order.customerName(), order == null ? null : order.serviceDate(),
                order == null ? null : order.priority(), order == null ? null : order.requestedWindowStart(),
                order == null ? null : order.requestedWindowEnd(), assignment.assignedWeightKg(),
                assignment.assignedVolumeM3(), assignment.assignedPallets(), assignment.wholeOrder(),
                assignment.assignedAt());
    }

    /**
     * @param tripStatus the parent trip's state, which decides whether the stop offers any action
     *     at all: stops are worked while the vehicle is out and at no other time, so a stop on a
     *     trip that has not left reports no allowed transitions even though its own outcome is
     *     PENDING. Deriving that in the client from two statuses is exactly the duplication
     *     {@code TripView.allowedTransitions} exists to prevent.
     */
    private static TripStopView toStopView(TripStop stop, MasterReference destination, long orderCount,
            TripStatus tripStatus, long openExceptionCount) {
        Set<StopExecutionStatus> allowed = tripStatus == TripStatus.IN_TRANSIT
                ? stop.executionStatus().allowedTransitions()
                : Set.of();
        return new TripStopView(stop.id(), stop.sequence(), stop.destinationId(),
                destination == null ? null : destination.code(), destination == null ? null : destination.name(),
                destination == null ? null : destination.latitude(), destination == null ? null : destination.longitude(),
                destination == null ? null : destination.address(),
                stop.serviceWindowStart(), stop.serviceWindowEnd(), orderCount,
                stop.executionStatus(), allowed, stop.actualArrivalAt(), stop.serviceStartedAt(),
                stop.actualDepartureAt(), stop.executionNotes(),
                dwellMinutes(stop.actualArrivalAt(), stop.actualDepartureAt()), (int) openExceptionCount,
                stop.etaArrivalAt(), stop.etaDepartureAt(), stop.etaSource(), stop.etaCalculatedAt(),
                stop.etaMissesWindow());
    }

    /** Null until both ends are known: a stop still being served has no dwell time yet, only a start. */
    private static Long dwellMinutes(OffsetDateTime arrival, OffsetDateTime departure) {
        return arrival == null || departure == null ? null : Duration.between(arrival, departure).toMinutes();
    }

    private static TripExceptionView toExceptionView(
            TripException exception, Map<UUID, TripStop> stops, Map<UUID, MasterReference> destinations) {
        TripStop stop = exception.tripStopId() == null ? null : stops.get(exception.tripStopId());
        MasterReference destination = stop == null ? null : destinations.get(stop.destinationId());
        return new TripExceptionView(exception.id(), exception.tripId(), exception.tripStopId(),
                stop == null ? null : stop.sequence(),
                destination == null ? null : destination.code(),
                destination == null ? null : destination.name(),
                exception.exceptionType(), exception.status(), exception.reportedAt(), exception.notes(),
                exception.resolvedAt(), exception.resolutionNotes());
    }
}
