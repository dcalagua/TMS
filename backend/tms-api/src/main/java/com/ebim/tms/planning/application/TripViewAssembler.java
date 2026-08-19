package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds the read models both planning services return, and is the single place that decides
 * <em>which</em> capacity a trip is reporting (live from its vehicle while draft, the frozen
 * snapshot once confirmed).
 *
 * <p>Everything here is batched. A board of N trips costs one grouped load query, one batched
 * vehicle lookup and one batched destination lookup - never N of anything. A trip detail adds one
 * assignment query and one batched order lookup, and touches no order line at all.
 */
@Service
public class TripViewAssembler {

    private final TripOrderAssignmentRepository assignmentRepository;
    private final VehicleLookupPort vehicleLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final OrderPlanningPort orderPlanningPort;
    private final PlanningCapacityService capacityService;

    public TripViewAssembler(TripOrderAssignmentRepository assignmentRepository, VehicleLookupPort vehicleLookupPort,
            DestinationLookupPort destinationLookupPort, OrderPlanningPort orderPlanningPort,
            PlanningCapacityService capacityService) {
        this.assignmentRepository = assignmentRepository;
        this.vehicleLookupPort = vehicleLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.orderPlanningPort = orderPlanningPort;
        this.capacityService = capacityService;
    }

    /** The board rows of a whole planning run, in a fixed number of queries. */
    public List<TripView> toViews(List<Trip> trips, UUID companyId) {
        if (trips.isEmpty()) {
            return List.of();
        }
        Map<UUID, CapacityLoad> loads = loadsOf(trips);
        Map<UUID, VehicleCapacityReference> vehicles = vehiclesOf(trips, companyId);
        return trips.stream().map(trip -> toView(trip, loads.getOrDefault(trip.id(), CapacityLoad.EMPTY), vehicles))
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
        TripView view = toView(trip, load, vehiclesOf(List.of(trip), companyId));

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

    private Map<UUID, CapacityLoad> loadsOf(List<Trip> trips) {
        Map<UUID, CapacityLoad> loads = new HashMap<>();
        List<UUID> tripIds = trips.stream().map(Trip::id).toList();
        for (TripOrderAssignmentRepository.TripLoadRow row
                : assignmentRepository.loadByTripIds(tripIds, AssignmentStatus.ACTIVE)) {
            loads.put(row.getTripId(), CapacityLoad.of(row));
        }
        return loads;
    }

    private TripView toView(Trip trip, CapacityLoad load, Map<UUID, VehicleCapacityReference> vehicles) {
        VehicleCapacityReference vehicle = trip.vehicleId() == null ? null : vehicles.get(trip.vehicleId());
        return new TripView(trip.id(), trip.planningRunId(), trip.tripNumber(), trip.status(), trip.vehicleId(),
                vehicle == null ? null : vehicle.code(), vehicle == null ? null : vehicle.licensePlate(),
                trip.carrierId(), vehicle == null ? null : vehicle.carrierName(), trip.plannedDepartureAt(),
                summarize(trip, load, vehicles), trip.stops().size(), load.orderCount(), trip.version(),
                trip.createdAt(), trip.updatedAt());
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
                stop.serviceWindowStart(), stop.serviceWindowEnd(), orderCount);
    }
}
