package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * The body of "this problem has been dealt with" (migration V27).
 *
 * <p>{@code notes} is required, and it is the only thing that makes resolving an exception worth
 * recording: "RESOLVED" with no explanation says a row was clicked, not that a customer was called
 * back and a redelivery arranged. The same reasoning {@code TripService.cancel} applies to a
 * cancellation reason on a confirmed trip.
 *
 * @param occurredAt when it was actually resolved. Optional: omitted means "now". Refused if it
 *     is before the problem was reported
 */
public record TripExceptionResolutionRequest(
        OffsetDateTime occurredAt,
        @NotBlank(message = "is required")
        @Size(max = 1000, message = "must be at most 1000 characters") String notes) {
}
