package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Setting or swapping a draft trip's vehicle, together with the departure it is planned for.
 *
 * <p>The carrier is not a field: it is resolved from the vehicle, so a plan can never name a
 * carrier that does not operate the truck. {@code TripService.updateVehicle} revalidates
 * everything already on the trip against the new vehicle's capacity and refuses the swap if the
 * current load no longer fits.
 */
public record TripVehicleRequest(
        @NotNull(message = "is required") UUID vehicleId,
        OffsetDateTime plannedDepartureAt,
        @NotNull(message = "is required") Long version) {
}
