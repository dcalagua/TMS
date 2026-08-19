package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Creating a trip inside a draft run. Both fields are optional: a planner routinely sketches
 * "trip 3" and decides which vehicle runs it after seeing what fits. A trip without a vehicle has
 * unlimited capacity and cannot be confirmed - see {@link CapacityLimits}.
 *
 * @param version the planning run's version, so creating a trip on a run someone else has just
 *                confirmed or cancelled fails loudly instead of silently attaching to it
 */
public record TripCreateRequest(
        UUID vehicleId,
        OffsetDateTime plannedDepartureAt,
        @NotNull(message = "is required") Long version) {
}
