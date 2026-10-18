package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.application.CapacityLimits;
import com.ebim.tms.planning.application.CapacityLoad;
import com.ebim.tms.planning.application.TripAssignmentService;
import com.ebim.tms.planning.application.TripRouteMetrics;
import com.ebim.tms.planning.application.TripRoutingService;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.TripCostingLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.math.BigDecimal;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link TripCostingLookupPort}: three repository reads and one vehicle
 * lookup, so it lives here beside {@link TripTrackingLookupAdapter} rather than in
 * {@code application}.
 *
 * <p>The one judgement it makes is {@link #isCostable}, and it is here for the reason the port
 * states: what a shipment's status means is planning's to say, not costing's.
 */
@Component
public class TripCostingLookupAdapter implements TripCostingLookupPort {

    /**
     * A shipment is worth pricing once it is binding and while it still happened.
     *
     * <p>{@code DRAFT} is excluded because everything a price depends on - the vehicle, the route,
     * the load - is still being rearranged, and an estimate that goes stale while the planner is
     * looking at it teaches them to ignore the number. {@code CANCELLED} is excluded because there
     * is no shipment to charge for; a cancellation fee, when one is agreed, is still recordable as
     * an <em>actual</em> cost, which is why {@code TripCostService.recordActual} does not consult
     * this list.
     */
    private static final Set<TripStatus> COSTABLE = Set.of(
            TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH, TripStatus.IN_TRANSIT, TripStatus.COMPLETED);

    private final TripRepository tripRepository;
    private final PlanningRunRepository planningRunRepository;
    private final VehicleLookupPort vehicleLookup;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final TripRoutingService tripRoutingService;
    private final TripAssignmentService tripAssignmentService;

    public TripCostingLookupAdapter(TripRepository tripRepository, PlanningRunRepository planningRunRepository,
            VehicleLookupPort vehicleLookup,
            OriginLookupPort originLookupPort, DestinationLookupPort destinationLookupPort,
            TripRoutingService tripRoutingService, TripAssignmentService tripAssignmentService) {
        this.tripAssignmentService = tripAssignmentService;
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
        this.vehicleLookup = vehicleLookup;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.tripRoutingService = tripRoutingService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CostableTrip> findCostableTrip(UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId).map(trip -> toCostable(trip, companyId));
    }

    /**
     * The shipment's measured run (V38). Resolved through the same service the trip workspace uses,
     * so the kilometres a controller sees on the screen are the kilometres the invoice was checked
     * against - two different numbers for one shipment is how a dispute starts.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> findMeasuredDistanceKm(UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId)
                .map(trip -> tripRoutingService.measure(trip, originReferenceOf(trip, companyId),
                        destinationsOf(trip, companyId)))
                .filter(metrics -> metrics.totalDistanceKm().signum() > 0)
                .map(TripRouteMetrics::totalDistanceKm);
    }

    private MasterReference originReferenceOf(Trip trip, UUID companyId) {
        UUID originId = originOf(trip, companyId);
        return originId == null
                ? null
                : originLookupPort.findAllInCompany(Set.of(originId), companyId).get(originId);
    }

    private Map<UUID, MasterReference> destinationsOf(Trip trip, UUID companyId) {
        Set<UUID> ids = trip.stops().stream().map(stop -> stop.destinationId())
                .collect(java.util.stream.Collectors.toSet());
        return ids.isEmpty() ? Map.of() : destinationLookupPort.findAllInCompany(ids, companyId);
    }

    private CostableTrip toCostable(Trip trip, UUID companyId) {
        VehicleCapacityReference vehicle = vehicleOf(trip, companyId);
        // The load on this trip's own vehicle, re-stacked for its height (ADR-022 section 5): the
        // loading metres a rate charges are the floor the stacks really cover in that vehicle.
        CapacityLimits limits = trip.hasCapacitySnapshot() ? CapacityLimits.ofSnapshot(trip)
                : vehicle == null ? CapacityLimits.unlimited() : CapacityLimits.of(vehicle);
        CapacityLoad load = tripAssignmentService.currentLoad(trip.id(), companyId, limits);
        return new CostableTrip(
                trip.id(),
                companyId,
                trip.shipmentNumber(),
                trip.planningDate(),
                trip.carrierId(),
                vehicle == null ? null : vehicle.vehicleTypeId(),
                originOf(trip, companyId),
                trip.routeId(),
                load.weightKg(),
                load.volumeM3(),
                load.pallets(),
                isCostable(trip),
                soleDestinationOf(trip),
                trip.stops().size(),
                load.floorAreaM2(),
                loadWidthOf(trip, vehicle),
                vehicle == null ? null : vehicle.floorFillFactor());
    }

    /**
     * The width loading metres are measured across (V63): what the trip froze at confirmation
     * (V62), else the vehicle type's current width. The snapshot first for the reason it exists -
     * a later fleet edit must not restate what a confirmed shipment costs.
     */
    private static BigDecimal loadWidthOf(Trip trip, VehicleCapacityReference vehicle) {
        if (trip.snapshotLoadWidthM() != null) {
            return trip.snapshotLoadWidthM();
        }
        return vehicle == null ? null : vehicle.widthM();
    }

    /**
     * The trip's one destination, or null when it has none or has more than one (V39).
     *
     * <p>Distinct stops rather than raw stop count: two orders to the same place are one
     * destination, and a shipment delivering twice to one customer is still on that lane.
     */
    private static UUID soleDestinationOf(Trip trip) {
        Set<UUID> destinations = trip.stops().stream()
                .map(stop -> stop.destinationId())
                .collect(java.util.stream.Collectors.toSet());
        return destinations.size() == 1 ? destinations.iterator().next() : null;
    }

    private static boolean isCostable(Trip trip) {
        return COSTABLE.contains(trip.status());
    }

    /**
     * The trip's origin, which it does not store: a trip departs from its run's origin, so the two
     * can never disagree (migration V11).
     */
    private UUID originOf(Trip trip, UUID companyId) {
        return planningRunRepository.findByIdAndCompanyId(trip.planningRunId(), companyId)
                .map(PlanningRun::originId)
                .orElse(null);
    }

    /**
     * Resolved through {@code findAllInCompany} and not {@code findAssignable}: a shipment already
     * on the road may be running a vehicle that has since gone into maintenance, and it still
     * costs what it costs. Validation of a <em>new</em> assignment is a different question, asked
     * elsewhere.
     */
    private VehicleCapacityReference vehicleOf(Trip trip, UUID companyId) {
        if (trip.vehicleId() == null) {
            return null;
        }
        return vehicleLookup.findAllInCompany(Set.of(trip.vehicleId()), companyId).get(trip.vehicleId());
    }
}
