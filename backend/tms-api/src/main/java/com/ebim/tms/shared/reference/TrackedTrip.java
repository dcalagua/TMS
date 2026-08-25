package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The little a position feed needs to know about a trip: which one it is, whether it is out on the
 * road, and what the vehicle running it is called.
 *
 * <p>Six fields where {@link PublishedShipment} has thirty-four, because tracking asks one question
 * of a trip and publication asks a partner to reconstruct it. A port that returned the fat record
 * "since it exists" would make every future change to what publication carries a change tracking
 * has to be re-reviewed for.
 *
 * @param status the {@code planning.domain.TripStatus} code, carried as a string for the
 *     module-boundary reason {@link PublishedShipment} documents. For display and for the sentence
 *     a refusal returns - never for deciding {@link #trackable()}, which is planning's answer
 * @param trackable whether a reported position against this trip is meaningful. Planning's
 *     decision, not the caller's: only the module that owns the lifecycle knows what its states
 *     mean, and a caller comparing status strings would be a second copy of that knowledge
 * @param vehicleCode the vehicle's TMS code, or null when none is assigned
 * @param vehicleLicensePlate what is painted on the truck - the identifier a telematics provider
 *     is most likely to key its own fleet on, and therefore what {@code TrackingProviderPort}
 *     asks with. A vendor keying on a device serial maps from this in its own adapter, where
 *     vendor-specific mapping belongs
 * @param actualDepartureAt when it really left, or null while it has not. Carried so tracking can
 *     say how long a shipment has been out without a second read
 */
public record TrackedTrip(
        UUID id,
        String shipmentNumber,
        String status,
        boolean trackable,
        String vehicleCode,
        String vehicleLicensePlate,
        OffsetDateTime actualDepartureAt) {
}
