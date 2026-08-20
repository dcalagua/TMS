package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * load, one grouped stop count, and the five lookups behind {@link ShipmentReferences} - never N
 * of anything. A trip detail adds one assignment query and one batched order lookup, and touches
 * no order line at all.
 */
@Service
public class TripViewAssembler {

    private final PlanningRunRepository planningRunRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final TripStopRepository tripStopRepository;
    private final OriginLookupPort originLookupPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final CarrierLookupPort carrierLookupPort;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final OrderPlanningPort orderPlanningPort;
    private final PlanningCapacityService capacityService;

    public TripViewAssembler(PlanningRunRepository planningRunRepository,
            TripOrderAssignmentRepository assignmentRepository, TripStopRepository tripStopRepository,
            OriginLookupPort originLookupPort, VehicleLookupPort vehicleLookupPort,
            CarrierLookupPort carrierLookupPort, RouteTemplateLookupPort routeTemplateLookupPort,
            DestinationLookupPort destinationLookupPort, OrderPlanningPort orderPlanningPort,
            PlanningCapacityService capacityService) {
        this.planningRunRepository = planningRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.tripStopRepository = tripStopRepository;
        this.originLookupPort = originLookupPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.carrierLookupPort = carrierLookupPort;
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

    /** One trip with its active assignments and ordered stops. */
    public TripDetailView toDetail(Trip trip, UUID companyId) {
        List<TripOrderAssignment> assignments =
                assignmentRepository.findByTripIdAndStatusOrderByAssignedAtAsc(trip.id(), AssignmentStatus.ACTIVE);
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(
                assignments.stream().map(TripOrderAssignment::orderId).collect(Collectors.toSet()), companyId);

        Set<UUID> destinationIds =
                new HashSet<>(orders.values().stream().map(PlannableOrder::destinationId).toList());
        trip.stops().forEach(stop -> destinationIds.add(stop.destinationId()));
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(destinationIds, companyId);

        CapacityLoad load = CapacityLoad.of(
                assignmentRepository.loadByTripId(trip.id(), AssignmentStatus.ACTIVE));
        // One trip: its stops are already being rendered below, so counting them in memory
        // costs nothing extra - unlike the board above, this is not an N+1.
        TripView view = toView(trip, load, referencesOf(List.of(trip), companyId), trip.stops().size());

        List<TripAssignmentView> assignmentViews = assignments.stream()
                .map(assignment -> toAssignmentView(assignment, orders.get(assignment.orderId()), destinations))
                .toList();
        Map<UUID, Long> ordersPerDestination = orders.values().stream()
                .collect(Collectors.groupingBy(PlannableOrder::destinationId, Collectors.counting()));
        List<TripStopView> stopViews = trip.stops().stream()
                .map(stop -> toStopView(stop, destinations.get(stop.destinationId()),
                        ordersPerDestination.getOrDefault(stop.destinationId(), 0L)))
                .toList();

        return new TripDetailView(view, assignmentViews, stopViews);
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
     */
    TripCapacityView summarize(Trip trip, CapacityLoad load, Map<UUID, VehicleCapacityReference> vehicles) {
        if (trip.status() == TripStatus.CONFIRMED) {
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
     * The five batched lookups behind a shipment header, for a whole set of trips at once.
     *
     * <p>The runs come first and are read company-scoped, not by id alone: the origin of a
     * shipment is its run's origin, so resolving it means resolving the run, and a run belonging
     * to another tenant must not be loadable even though the trips that named it were already
     * scoped.
     */
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
                carrierLookupPort.findAllInCompany(idsOf(trips, Trip::carrierId), companyId);
        Map<UUID, RouteTemplate> routes =
                routeTemplateLookupPort.findAllInCompany(idsOf(trips, Trip::routeId), companyId);
        return new ShipmentReferences(runs, origins, vehiclesOf(trips, companyId), carriers, routes);
    }

    private static Set<UUID> idsOf(List<Trip> trips, Function<Trip, UUID> extractor) {
        return trips.stream().map(extractor).filter(Objects::nonNull).collect(Collectors.toSet());
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
                trip.routeId(), route == null ? null : route.code(), route == null ? null : route.name(),
                trip.plannedDepartureAt(), summarize(trip, load, references.vehicles()), (int) stopCount,
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

    private static TripStopView toStopView(TripStop stop, MasterReference destination, long orderCount) {
        return new TripStopView(stop.id(), stop.sequence(), stop.destinationId(),
                destination == null ? null : destination.code(), destination == null ? null : destination.name(),
                destination == null ? null : destination.latitude(), destination == null ? null : destination.longitude(),
                stop.serviceWindowStart(), stop.serviceWindowEnd(), orderCount);
    }
}
