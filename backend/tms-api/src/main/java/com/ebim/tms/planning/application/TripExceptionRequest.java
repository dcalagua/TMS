package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripExceptionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The body of "something went wrong on this trip" (migration V27).
 *
 * <p>Reported on its own, unlike the exception a skipped or failed stop opens: a traffic jam or a
 * breakdown is a problem that has not (yet) changed any stop's outcome, and forcing a dispatcher
 * to fail a delivery in order to record one would put a lie in the delivery history.
 *
 * @param tripStopId the stop this is about, or null when it is the trip's. Required for the four
 *     types that are statements about a delivery - {@code TripExceptionType.requiresStop}
 * @param occurredAt when the problem happened. Optional: omitted means "now"
 * @param notes what happened. Required for {@code OTHER}
 */
public record TripExceptionRequest(
        UUID tripStopId,
        @NotNull(message = "is required") TripExceptionType exceptionType,
        OffsetDateTime occurredAt,
        @Size(max = 1000, message = "must be at most 1000 characters") String notes) {
}
