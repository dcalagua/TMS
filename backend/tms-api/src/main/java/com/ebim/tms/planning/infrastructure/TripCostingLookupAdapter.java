package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.TripCostingLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.math.BigDecimal;
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
    private final TripOrderAssignmentRepository assignmentRepository;
    private final VehicleLookupPort vehicleLookup;

    public TripCostingLookupAdapter(TripRepository tripRepository, PlanningRunRepository planningRunRepository,
            TripOrderAssignmentRepository assignmentRepository, VehicleLookupPort vehicleLookup) {
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.vehicleLookup = vehicleLookup;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CostableTrip> findCostableTrip(UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId).map(trip -> toCostable(trip, companyId));
    }

    private CostableTrip toCostable(Trip trip, UUID companyId) {
        TripOrderAssignmentRepository.TripLoad load =
                assignmentRepository.loadByTripId(trip.id(), AssignmentStatus.ACTIVE);
        return new CostableTrip(
                trip.id(),
                companyId,
                trip.shipmentNumber(),
                trip.planningDate(),
                trip.carrierId(),
                vehicleTypeIdOf(trip, companyId),
                originOf(trip, companyId),
                trip.routeId(),
                zeroIfNull(load == null ? null : load.getWeightKg()),
                zeroIfNull(load == null ? null : load.getVolumeM3()),
                zeroIfNull(load == null ? null : load.getPallets()),
                isCostable(trip));
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
    private UUID vehicleTypeIdOf(Trip trip, UUID companyId) {
        if (trip.vehicleId() == null) {
            return null;
        }
        VehicleCapacityReference vehicle =
                vehicleLookup.findAllInCompany(Set.of(trip.vehicleId()), companyId).get(trip.vehicleId());
        return vehicle == null ? null : vehicle.vehicleTypeId();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
