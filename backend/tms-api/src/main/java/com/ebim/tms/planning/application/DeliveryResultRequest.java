package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.DeliveryQuantities;
import com.ebim.tms.shared.reference.OrderAmounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
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
        @Size(max = 1000, message = "must be at most 1000 characters") String notes,
        /**
         * How much was taken to the customer, how much they took and how much they refused
         * (migration V45, debt D3).
         *
         * <p><b>Optional, and null means "not recorded" - never zero.</b> Recording an outcome
         * without amounts stays a legitimate way to work, and is what every delivery written before
         * V45 did. A caller that omits this block is making no claim about quantities; a caller that
         * sends zeros is claiming the customer took nothing.
         */
        @Valid DeliveryQuantitiesRequest quantities) {

    /**
     * An outcome with no amounts - what every delivery recorded before V45 was, and a legitimate
     * way to work still. Kept as its own constructor rather than making callers pass an explicit
     * null, so "I am not claiming a quantity" reads differently from "I forgot the argument".
     */
    public DeliveryResultRequest(DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
            String receiverDocument, String notes) {
        this(result, deliveredAt, receiverName, receiverDocument, notes, null);
    }

    /**
     * The three measures, each optional as a whole.
     *
     * <p>Deliberately not defaulted to zero by the binder: {@code null} has to survive all the way
     * to {@code DeliveryQuantities}, which is the type that knows absence is not a delivery of
     * nothing.
     */
    public record DeliveryQuantitiesRequest(
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal attemptedWeightKg,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal attemptedVolumeM3,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal attemptedPallets,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal deliveredWeightKg,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal deliveredVolumeM3,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal deliveredPallets,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal refusedWeightKg,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal refusedVolumeM3,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
            BigDecimal refusedPallets) {

        public DeliveryQuantities toDomain() {
            return DeliveryQuantities.of(
                    new OrderAmounts(attemptedWeightKg, attemptedVolumeM3, attemptedPallets),
                    new OrderAmounts(deliveredWeightKg, deliveredVolumeM3, deliveredPallets),
                    new OrderAmounts(refusedWeightKg, refusedVolumeM3, refusedPallets));
        }
    }
}
