package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.application.TripRouteMetrics;
import com.ebim.tms.planning.application.TripRoutingService;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.OwnFleetTripLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link OwnFleetTripLookupPort} (V48, JOB 22).
 *
 * <p>Lives here because it reads {@link Trip}, which the planning module owns. Every figure it
 * returns is derived exactly the way an existing adapter derives it - the window like
 * {@link TripSchedulingAdapter}, the distance like {@link TripCostingLookupAdapter} - so an
 * own-fleet estimate and a carrier price disagree about a trip's distance only if one of them is
 * broken.
 */
@Component
public class OwnFleetTripLookupAdapter implements OwnFleetTripLookupPort {

    private final TripRepository tripRepository;
    private final VehicleLookupPort vehicleLookup;
    private final TripRoutingService tripRoutingService;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final PlanningRunRepository planningRunRepository;

    public OwnFleetTripLookupAdapter(TripRepository tripRepository, VehicleLookupPort vehicleLookup,
            TripRoutingService tripRoutingService, OriginLookupPort originLookupPort,
            DestinationLookupPort destinationLookupPort, PlanningRunRepository planningRunRepository) {
        this.tripRepository = tripRepository;
        this.vehicleLookup = vehicleLookup;
        this.tripRoutingService = tripRoutingService;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.planningRunRepository = planningRunRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OwnFleetCostableTrip> findOwnFleetCostableTrip(UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId).map(trip -> {
            List<TripStop> stops = trip.stops();
            TripStop last = stops.stream().max(Comparator.comparingInt(TripStop::sequence)).orElse(null);
            return new OwnFleetCostableTrip(
                    trip.id(),
                    trip.vehicleId(),
                    vehicleTypeIdOf(trip, companyId),
                    trip.plannedDepartureAt(),
                    // The V43 ETA of the last stop's departure, left null when a leg could not be
                    // measured. Same rule as V47's scheduling: an unknown end is not a guessable
                    // one, and here it means the time-based components have no quantity rather
                    // than a quantity of zero.
                    last == null ? null : last.etaDepartureAt(),
                    measuredDistanceKm(trip, companyId),
                    trip.carrierId());
        });
    }

    /**
     * Null rather than zero when the trip's stops have no coordinates.
     *
     * <p>{@code TripRouteMetrics} reports zero kilometres for a route it could not measure, which is
     * right for planning and wrong here: multiplied by a fuel rate it produces a cost of nothing
     * that looks calculated. Filtered to a positive distance for exactly the reason V39 filters it.
     */
    private BigDecimal measuredDistanceKm(Trip trip, UUID companyId) {
        TripRouteMetrics metrics = tripRoutingService.measure(
                trip, originReferenceOf(trip, companyId), destinationsOf(trip, companyId));
        if (metrics == null || metrics.totalDistanceKm() == null || metrics.totalDistanceKm().signum() <= 0) {
            return null;
        }
        return metrics.totalDistanceKm();
    }

    private MasterReference originReferenceOf(Trip trip, UUID companyId) {
        // findByIdAndCompanyId, never findById: a bare primary key knows nothing about tenancy, and
        // JOB 15's guard exists precisely to catch this. Caught by it here.
        UUID originId = planningRunRepository
                .findByIdAndCompanyId(trip.planningRunId(), companyId)
                .map(com.ebim.tms.planning.domain.PlanningRun::originId).orElse(null);
        return originId == null
                ? null
                : originLookupPort.findAllInCompany(Set.of(originId), companyId).get(originId);
    }

    private Map<UUID, MasterReference> destinationsOf(Trip trip, UUID companyId) {
        Set<UUID> ids = trip.stops().stream().map(TripStop::destinationId)
                .collect(java.util.stream.Collectors.toSet());
        return ids.isEmpty() ? Map.of() : destinationLookupPort.findAllInCompany(ids, companyId);
    }

    private UUID vehicleTypeIdOf(Trip trip, UUID companyId) {
        if (trip.vehicleId() == null) {
            return null;
        }
        VehicleCapacityReference vehicle =
                vehicleLookup.findAllInCompany(Set.of(trip.vehicleId()), companyId).get(trip.vehicleId());
        return vehicle == null ? null : vehicle.vehicleTypeId();
    }
}
