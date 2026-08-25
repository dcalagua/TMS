package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Naming, swapping or clearing a trip's driver.
 *
 * <p>{@code driverId} is nullable on purpose, and that is the whole difference from
 * {@link TripVehicleRequest}: a shipment with no vehicle cannot be confirmed, so "no vehicle" is
 * never a state worth submitting, while "the driver we had is off and we do not have another
 * yet" is an ordinary thing for a dispatcher to record. Sending null clears the assignment and
 * releases the driver for another trip the same day.
 *
 * <p>{@code version} is required, like every other operation that edits a field the caller read:
 * two dispatchers assigning different drivers to the same shipment from the same stale board must
 * not both silently succeed.
 */
public record TripDriverRequest(
        UUID driverId,
        @NotNull(message = "is required") Long version) {
}
