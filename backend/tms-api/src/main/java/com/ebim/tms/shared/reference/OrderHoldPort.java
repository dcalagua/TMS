package com.ebim.tms.shared.reference;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which orders are held, asked by {@code planning} without depending on {@code com.ebim.tms.orders}
 * (ADR-014 section 7). {@code orders.application.OrderHoldService} is the only implementation.
 *
 * <p>Read-only on purpose. A hold is placed and lifted in the orders module, by a person with
 * {@code orders.hold:manage}; planning only needs to know that one exists - to refuse a dispatch
 * ({@code DispatchReadiness}) and to warn a supervisor (the Control Tower advisory
 * {@code ORDER_HOLD_ON_COMMITTED_TRIP}).
 */
public interface OrderHoldPort {

    /**
     * One order with at least one active blocking hold.
     *
     * @param holdTypes the types of its active blocking holds, distinct, in the order they were placed
     */
    record HeldOrder(UUID orderId, String orderNumber, List<String> holdTypes) {

        public HeldOrder {
            holdTypes = List.copyOf(holdTypes);
        }
    }

    /**
     * Of {@code orderIds}, the ones with an active blocking hold, in one call. Orders of another
     * company are never returned, whatever ids are asked about.
     */
    Map<UUID, HeldOrder> activeBlockingHolds(Set<UUID> orderIds, UUID companyId);
}
