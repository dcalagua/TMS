package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Assigning one whole order to a trip. Quantities are deliberately absent: V1 assigns whole
 * orders, and the amounts are snapshotted server-side from the order's own totals - a client that
 * could send "this order weighs 100 kg" could plan a full truck as an empty one.
 */
public record AssignOrderRequest(@NotNull(message = "is required") UUID orderId) {
}
