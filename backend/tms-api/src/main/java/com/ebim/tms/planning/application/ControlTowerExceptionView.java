package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.planning.domain.TripStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the open-exceptions panel: a problem raised on one of today's shipments that nobody
 * has closed out.
 *
 * <p>Trip-level on purpose. {@code TripExceptionView} resolves the stop's sequence and destination
 * because the trip workspace has already loaded that trip's stops; doing the same across every
 * shipment of the day would mean reading a stop and a destination master for each row of a panel
 * whose job is to say "go and look at SH-00000142". {@code tripStopId} is carried so a screen can
 * still deep-link to the stop, and the workspace resolves the name when it gets there.
 *
 * @param tripStatus where the shipment is now, which changes what the problem means: the same
 *     BREAKDOWN on a trip still in the yard and on one halfway through its stops are two different
 *     mornings
 * @param reportedAt when the problem happened - operator-supplied, not when it was typed
 *     ({@code TripException})
 */
public record ControlTowerExceptionView(
        UUID id,
        UUID tripId,
        String shipmentNumber,
        TripStatus tripStatus,
        UUID tripStopId,
        TripExceptionType exceptionType,
        OffsetDateTime reportedAt,
        String notes) {
}
