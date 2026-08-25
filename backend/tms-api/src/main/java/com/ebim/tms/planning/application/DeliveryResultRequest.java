package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * The body of "record what happened to this order at this stop" (migration V28).
 *
 * <p><b>No {@code version}</b>, for the reason {@link TripStopExecutionRequest} gives: the guard is
 * the trip's row lock, and a dispatcher recording nine deliveries at one stop must not have to
 * reload the shipment between each of them.
 *
 * <p>The endpoint is a {@code PUT} and this body is the whole state of the delivery, not a patch: a
 * correction that omitted {@code receiverName} means "there is no receiver", not "leave the one
 * that is there". Anything else would make it impossible to remove a name typed by mistake -
 * exactly the field somebody would want removed.
 *
 * <p>Which combinations are legal is {@link DeliveryResult}'s answer and is checked with readable
 * messages by {@code TripDeliveryService}: a handover carries the moment it happened, only a result
 * reached with somebody present may name a receiver, and anything short of a clean delivery is
 * explained. Bean Validation here only checks what is true of every result.
 *
 * @param result what happened to the goods; the one field that is always required
 * @param deliveredAt when they changed hands - the operator's own time, so an end-of-day paperwork
 *     run records the morning it belongs to. Required for {@code DELIVERED} and {@code PARTIAL},
 *     refused for {@code NOT_ATTEMPTED}, optional for the rest
 * @param receiverName who took them, or who refused them. Optional everywhere: plenty of
 *     deliveries are left at a dock with a stamp and no name
 * @param receiverDocument their identity document, where a company asks for one. Personal data
 *     kept only because a disputed delivery is settled with it
 * @param notes what was short, why it was refused, why the attempt failed
 */
public record DeliveryResultRequest(
        @NotNull(message = "is required") DeliveryResult result,
        OffsetDateTime deliveredAt,
        @Size(max = 120, message = "must be at most 120 characters") String receiverName,
        @Size(max = 60, message = "must be at most 60 characters") String receiverDocument,
        @Size(max = 1000, message = "must be at most 1000 characters") String notes) {
}
