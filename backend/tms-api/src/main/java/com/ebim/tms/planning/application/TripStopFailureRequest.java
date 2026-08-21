package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripExceptionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * The body of the two stop outcomes that mean a delivery did not happen: skipped and failed
 * (migration V27).
 *
 * <p>{@code exceptionType} is <b>mandatory</b>, and that is the point of this being a separate
 * request from {@link TripStopExecutionRequest}. A stop that was not served without a typed reason
 * on file is the exact gap per-stop execution exists to close: it makes "how many deliveries did
 * we miss last week, and why" a query rather than a reading exercise over free text.
 * {@code TripStopExecutionService} opens a {@code TripException} of this type in the same
 * transaction as the transition.
 *
 * @param exceptionType why the stop was not served. Any value of the catalogue is accepted, the
 *     journey-shaped ones included: "we failed this delivery because the truck broke down" is a
 *     true sentence, and refusing it would only push the real reason into free text
 * @param notes the sentence a human needs. Required when the type is {@code OTHER}, which says
 *     nothing on its own
 */
public record TripStopFailureRequest(
        OffsetDateTime occurredAt,
        @NotNull(message = "is required") TripExceptionType exceptionType,
        @Size(max = 500, message = "must be at most 500 characters") String notes) {
}
