package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.OrderAmounts;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What to put on a trip: an order, and optionally only part of it (migration V37).
 *
 * <p><b>The three amounts are all-or-nothing together and optional as a group.</b> Omitting them
 * means "everything of this order that is still unplanned", which is what the board sends for the
 * ordinary case and is why every existing caller keeps working unchanged. Sending them means a
 * split: 70 of the 100 pallets go on this truck and the rest waits for another.
 *
 * <p>Partial values are not a percentage and not a line selection. They are the same three measures
 * the order, the assignment and the vehicle type are all expressed in - see {@link OrderAmounts}
 * for why this product has no fourth "quantity" measure to split by.
 *
 * <p>The server never trusts them as a load figure on their own: the allocation is refused if it
 * exceeds what is still pending, by the service and again by
 * {@code ck_transport_order_not_over_allocated}.
 */
public record AssignOrderRequest(
        @NotNull(message = "is required") UUID orderId,
        @DecimalMin(value = "0", message = "cannot be negative") BigDecimal weightKg,
        @DecimalMin(value = "0", message = "cannot be negative") BigDecimal volumeM3,
        @DecimalMin(value = "0", message = "cannot be negative") BigDecimal pallets) {

    /** The single-argument shape every pre-V37 caller used: assign whatever is left of the order. */
    public AssignOrderRequest(UUID orderId) {
        this(orderId, null, null, null);
    }

    /** Whether the caller asked for a specific slice rather than the remainder. */
    public boolean isPartial() {
        return weightKg != null || volumeM3 != null || pallets != null;
    }

    /**
     * The slice asked for. A measure the caller left out is zero rather than "all of it": a split
     * that names 70 pallets and says nothing about weight is asking for pallets, and silently
     * loading the order's whole weight onto that truck would be the opposite of what was typed.
     */
    public OrderAmounts amounts() {
        return new OrderAmounts(weightKg, volumeM3, pallets);
    }
}
