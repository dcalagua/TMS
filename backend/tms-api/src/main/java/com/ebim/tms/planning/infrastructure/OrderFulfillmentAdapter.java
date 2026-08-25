package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.shared.reference.OrderFulfillmentPort;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link OrderFulfillmentPort}: one indexed read of
 * {@code tms.order_delivery} and a translation.
 *
 * <p>The translation is why this lives in planning rather than in {@code shared}. {@link
 * DeliveryResult} is planning's vocabulary and stays inside planning; what crosses the boundary is
 * {@link OrderFulfillmentStatus}, which is the same statement in words the rest of the product may
 * hold. Putting the mapping in {@code shared} would drag the delivery enum out with it.
 */
@Component
public class OrderFulfillmentAdapter implements OrderFulfillmentPort {

    private final OrderDeliveryRepository deliveries;

    public OrderFulfillmentAdapter(OrderDeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrderFulfillmentStatus> fulfillmentOf(Collection<UUID> orderIds, UUID companyId) {
        Set<UUID> wanted = Set.copyOf(orderIds);
        if (wanted.isEmpty()) {
            return Map.of();
        }

        // PENDING for everything first, then overwritten by whatever was actually recorded. An
        // order the caller asked about that has no row - or that belongs to another company, and
        // so is not returned by the query - comes back PENDING rather than absent.
        Map<UUID, OrderFulfillmentStatus> byOrder = new HashMap<>();
        wanted.forEach(orderId -> byOrder.put(orderId, OrderFulfillmentStatus.PENDING));

        Map<UUID, OrderDelivery> latest = new HashMap<>();
        for (OrderDelivery delivery : deliveries.findByCompanyIdAndOrderIdIn(companyId, wanted)) {
            // uq_order_delivery_stop_order (V28) is per stop, not per order, so an order that was
            // moved to another trip after a failed attempt has a row for each. The current answer
            // is the most recently recorded one; the earlier attempts are history, and the trip's
            // own deliveries list is where they are read.
            latest.merge(delivery.orderId(), delivery,
                    (existing, candidate) -> candidate.recordedAt().isAfter(existing.recordedAt())
                            ? candidate
                            : existing);
        }
        latest.forEach((orderId, delivery) -> byOrder.put(orderId, translate(delivery.result())));
        return Map.copyOf(byOrder);
    }

    /**
     * One value each way, deliberately exhaustive and deliberately without a default: a new
     * delivery result added to planning must be given a meaning out here, and a {@code switch}
     * over an enum with no default is what makes the compiler say so.
     */
    private static OrderFulfillmentStatus translate(DeliveryResult result) {
        return switch (result) {
            case DELIVERED -> OrderFulfillmentStatus.DELIVERED;
            case PARTIAL -> OrderFulfillmentStatus.PARTIALLY_DELIVERED;
            case REJECTED -> OrderFulfillmentStatus.REJECTED;
            case FAILED -> OrderFulfillmentStatus.FAILED;
            case NOT_ATTEMPTED -> OrderFulfillmentStatus.NOT_ATTEMPTED;
        };
    }
}
