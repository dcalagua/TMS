package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * When a shipment runs, where it starts and ends, and whether this vehicle may run it
 * (migration V47).
 *
 * <p>Answered by {@code planning}, which owns {@code tms.trip} and {@code tms.trip_stop}. Read by
 * {@code fleet}, which builds the day. Kept apart from {@link TripSettlementLookupPort} because the
 * questions are different - settlement asks who ran it and what it cost, scheduling asks when and
 * where - and one port answering both would grow into a window onto the trip table.
 */
public interface TripSchedulingPort {

    /** What each of these shipments is, keyed by trip id. Absent means it is not this company's. */
    Map<UUID, TripSchedule> findSchedules(Collection<UUID> tripIds, UUID companyId);

    /**
     * @param startsAt        the planned departure, or null when the shipment has none
     * @param endsAt          when its last stop finishes, from the V43 ETA, or <b>null when an ETA
     *                        leg could not be measured</b>. Never a guess: a day built on an
     *                        unknown end is a day nobody has checked
     * @param startLocationId where the vehicle leaves from - the planning run's origin
     * @param endLocationId   the last stop's destination, or null when the shipment has no stops
     * @param carrierId       the owner of the assigned vehicle
     * @param acceptedCarrierId the carrier that accepted a tender, or null (V42). When the two
     *                        differ the shipment cannot depart, and scheduling must report that
     *                        rather than repair it
     */
    record TripSchedule(
            UUID tripId,
            String shipmentNumber,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID startLocationId,
            UUID endLocationId,
            UUID carrierId,
            UUID acceptedCarrierId) {

        /**
         * Whether this shipment may run on a vehicle owned by {@code vehicleCarrierId}.
         *
         * <p>Mirrors {@code Trip.awaitsCarrierVehicle} exactly. Asked here so the day's validator
         * can <em>report</em> the conflict; it is never repaired, because a work assignment is not
         * an alternative route past a dispatch guard.
         */
        public boolean carrierMatches(UUID vehicleCarrierId) {
            if (acceptedCarrierId == null) {
                return true;
            }
            return acceptedCarrierId.equals(carrierId) && acceptedCarrierId.equals(vehicleCarrierId);
        }
    }
}
