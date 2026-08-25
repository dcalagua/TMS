package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TripTrackingLookupPort;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link TripTrackingLookupPort}: two repository reads and one batched
 * vehicle lookup, so it lives here beside {@code ShipmentPublicationAdapter} rather than in
 * {@code application}.
 *
 * <p>The one judgement it makes is {@code TRACKABLE}, and it is here rather than in the tracking
 * module for the reason the port states: what {@code IN_TRANSIT} means is planning's to say.
 */
@Component
public class TripTrackingLookupAdapter implements TripTrackingLookupPort {

    /**
     * A position is meaningful once the vehicle has left, and stays meaningful after the trip is
     * closed.
     *
     * <p>{@code COMPLETED} is on the list deliberately, and it is the only entry that needs an
     * argument. A feed buffers: the last few pings of a delivery day routinely arrive after the
     * dispatcher has already pressed "complete", and refusing them would turn a normal race into a
     * per-day burst of refusals a partner cannot fix from their side. They cost nothing - the
     * sampling interval bounds how many are kept and retention removes them on the same schedule
     * as the rest.
     *
     * <p>{@code READY_FOR_DISPATCH} is not on the list even though the truck is often loaded and
     * warm: the trip has not left, and a map showing it moving would contradict its own status.
     * {@code DRAFT}, {@code CONFIRMED} and {@code CANCELLED} are refusals for the plainer reason
     * that a sender reporting against them is pointing at the wrong shipment.
     */
    private static final Set<TripStatus> TRACKABLE = Set.of(TripStatus.IN_TRANSIT, TripStatus.COMPLETED);

    private final TripRepository tripRepository;
    private final VehicleLookupPort vehicleLookup;

    public TripTrackingLookupAdapter(TripRepository tripRepository, VehicleLookupPort vehicleLookup) {
        this.tripRepository = tripRepository;
        this.vehicleLookup = vehicleLookup;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrackedTrip> findById(UUID companyId, UUID tripId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId)
                .map(trip -> toReference(trip, vehicleOf(trip, companyId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, TrackedTrip> findByShipmentNumbers(UUID companyId, List<String> shipmentNumbers) {
        if (shipmentNumbers.isEmpty()) {
            return Map.of();
        }
        List<Trip> trips = tripRepository.findByShipmentNumberInAndCompanyId(Set.copyOf(shipmentNumbers), companyId);
        Map<UUID, VehicleCapacityReference> vehicles = vehiclesOf(trips, companyId);

        Map<String, TrackedTrip> byNumber = new LinkedHashMap<>();
        for (Trip trip : trips) {
            byNumber.put(trip.shipmentNumber(), toReference(trip, vehicles.get(trip.vehicleId())));
        }
        return byNumber;
    }

    /**
     * One batched call for the whole run rather than one per trip - the N+1 discipline
     * {@code TripViewAssembler} established, and the reason this port is batch-shaped at all.
     */
    private Map<UUID, VehicleCapacityReference> vehiclesOf(List<Trip> trips, UUID companyId) {
        Set<UUID> vehicleIds = new HashSet<>();
        for (Trip trip : trips) {
            if (trip.vehicleId() != null) {
                vehicleIds.add(trip.vehicleId());
            }
        }
        return vehicleIds.isEmpty() ? new HashMap<>() : vehicleLookup.findAllInCompany(vehicleIds, companyId);
    }

    private VehicleCapacityReference vehicleOf(Trip trip, UUID companyId) {
        if (trip.vehicleId() == null) {
            return null;
        }
        return vehicleLookup.findAllInCompany(Set.of(trip.vehicleId()), companyId).get(trip.vehicleId());
    }

    private static TrackedTrip toReference(Trip trip, VehicleCapacityReference vehicle) {
        return new TrackedTrip(
                trip.id(),
                trip.shipmentNumber(),
                trip.status().name(),
                TRACKABLE.contains(trip.status()),
                vehicle == null ? null : vehicle.code(),
                vehicle == null ? null : vehicle.licensePlate(),
                trip.actualDepartureAt());
    }
}
