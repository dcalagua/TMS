package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.OrderAmounts;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
        @DecimalMin(value = "0", message = "cannot be negative") BigDecimal pallets,
        /**
         * Square metres of floor the slice takes (V62, ADR-022 section 5). Unlike the other three,
         * omitted is <em>not</em> zero: floor is not something a planner weighs or counts, and a slice
         * carrying goods on no floor would leave the order's floor pending forever, so it could never
         * become PLANNED. Omitted, it is derived ({@link #amountsAgainst}): the order's pending floor
         * in the largest proportion the stated measures take of their pending - so the slice that
         * completes any measure takes the rest of the floor, and the remainder converges.
         */
        @DecimalMin(value = "0", message = "cannot be negative") BigDecimal floorAreaM2) {

    /** {@code allocated_floor_area_m2} is numeric(12,4). */
    private static final int FLOOR_SCALE = 4;

    private static final int SHARE_SCALE = 10;

    /** The single-argument shape every pre-V37 caller used: assign whatever is left of the order. */
    public AssignOrderRequest(UUID orderId) {
        this(orderId, null, null, null, null);
    }

    /** The V37 shape: three amounts, no floor. */
    public AssignOrderRequest(UUID orderId, BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {
        this(orderId, weightKg, volumeM3, pallets, null);
    }

    /** Whether the caller asked for a specific slice rather than the remainder. */
    public boolean isPartial() {
        return weightKg != null || volumeM3 != null || pallets != null || floorAreaM2 != null;
    }

    /**
     * The slice asked for. A measure the caller left out is zero rather than "all of it": a split
     * that names 70 pallets and says nothing about weight is asking for pallets, and silently
     * loading the order's whole weight onto that truck would be the opposite of what was typed.
     */
    public OrderAmounts amounts() {
        return new OrderAmounts(weightKg, volumeM3, pallets, floorAreaM2, null);
    }

    /**
     * The slice against what is still pending of the order: {@link #amounts()}, with the floor derived
     * when it was omitted and the order has floor pending (see {@code floorAreaM2}).
     */
    public OrderAmounts amountsAgainst(OrderAmounts pending) {
        OrderAmounts stated = amounts();
        if (floorAreaM2 != null || pending.floorAreaM2().signum() <= 0) {
            return stated;
        }
        BigDecimal share = BigDecimal.ZERO;
        share = share.max(ratio(weightKg, pending.weightKg()));
        share = share.max(ratio(volumeM3, pending.volumeM3()));
        share = share.max(ratio(pallets, pending.pallets()));
        BigDecimal floor = pending.floorAreaM2().multiply(share.min(BigDecimal.ONE))
                .setScale(FLOOR_SCALE, RoundingMode.HALF_UP).min(pending.floorAreaM2());
        return new OrderAmounts(stated.weightKg(), stated.volumeM3(), stated.pallets(), floor, null);
    }

    private static BigDecimal ratio(BigDecimal stated, BigDecimal pending) {
        if (stated == null || pending == null || pending.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return stated.divide(pending, SHARE_SCALE, RoundingMode.HALF_UP);
    }
}
