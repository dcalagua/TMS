package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.shared.reference.AppointmentTripPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planning's answers to the three questions appointments asks (migration V41).
 *
 * <p>Lives here, in planning, because these are planning's facts: a shipment and its stops belong to
 * this module and the appointments module must not read their tables. The direction is the same one
 * {@code OrderPlanningPort} and {@code RoutingPort} run in - the module that owns the data answers.
 */
@Component
class AppointmentTripAdapter implements AppointmentTripPort {

    private final TripRepository tripRepository;

    AppointmentTripAdapter(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean tripExists(UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId).isPresent();
    }

    /**
     * Whether the stop is on that shipment.
     *
     * <p>Loaded through the trip rather than by stop id alone, so a stop id from another company's
     * shipment cannot be attached to this one's booking - the same shape
     * {@code TripDeliveryService.requireDelivery} uses, and for the same reason.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean stopBelongsToTrip(UUID tripStopId, UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId)
                .map(trip -> trip.stops().stream().anyMatch(stop -> tripStopId.equals(stop.id())))
                .orElse(false);
    }
}
