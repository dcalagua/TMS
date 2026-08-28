package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DeliveryQuantities;
import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderFulfillmentPort;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Resolved lazily to break the cycle this adapter would otherwise close: the orders module's
     * {@code OrderPlanningService} implements {@code OrderPlanningPort} and already depends on
     * planning's fulfilment view, so injecting it eagerly makes the two beans require each other at
     * construction. {@code @Lazy} is the narrowest fix and keeps the port boundary intact.
     */
    private final OrderPlanningPort orderPlanningPort;

    public OrderFulfillmentAdapter(OrderDeliveryRepository deliveries,
            @org.springframework.context.annotation.Lazy OrderPlanningPort orderPlanningPort) {
        this.deliveries = deliveries;
        this.orderPlanningPort = orderPlanningPort;
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

        // Every attempt, not just the latest: with quantities recorded (V45) an order delivered 60
        // on Monday and 40 on Tuesday has received all of it, and only the sum can say so.
        Map<UUID, List<OrderDelivery>> attemptsByOrder = new HashMap<>();
        Map<UUID, OrderDelivery> latest = new HashMap<>();
        for (OrderDelivery delivery : deliveries.findByCompanyIdAndOrderIdIn(companyId, wanted)) {
            attemptsByOrder.computeIfAbsent(delivery.orderId(), key -> new ArrayList<>()).add(delivery);
            // uq_order_delivery_stop_order (V28) is per stop, not per order, so an order that was
            // moved to another trip after a failed attempt has a row for each. The current answer
            // is the most recently recorded one; the earlier attempts are history, and the trip's
            // own deliveries list is where they are read.
            latest.merge(delivery.orderId(), delivery,
                    (existing, candidate) -> candidate.recordedAt().isAfter(existing.recordedAt())
                            ? candidate
                            : existing);
        }
        // Orders whose attempts carry amounts are decided by the amounts; the rest keep the
        // outcome-only reading they have always had. Resolved in one batched call, never per row.
        Set<UUID> quantified = attemptsByOrder.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(d -> d.quantities().isRecorded()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, PlannableOrder> demands = quantified.isEmpty()
                ? Map.of()
                : orderPlanningPort.findAllInCompany(quantified, companyId);

        latest.forEach((orderId, delivery) -> {
            PlannableOrder demand = demands.get(orderId);
            if (demand == null) {
                byOrder.put(orderId, translate(delivery.result()));
                return;
            }
            byOrder.put(orderId, fromQuantities(attemptsByOrder.get(orderId), demand, delivery.result()));
        });
        return Map.copyOf(byOrder);
    }

    /**
     * The lifecycle read from amounts rather than from an outcome (V45, debt D3).
     *
     * <p>Used only when at least one attempt recorded quantities. Everything else keeps the
     * outcome-only reading it has always had, so <b>no historical order changes meaning</b> - the
     * backward compatibility this feature is required to preserve.
     *
     * <p><b>Summed across attempts.</b> An order that received 60 on Monday and 40 on Tuesday has
     * received all of it, and the latest row alone cannot say so. Attempts that recorded no
     * amounts contribute nothing rather than zero - they are silent, not empty.
     *
     * <p>Judged against the order's own demand, because that is what "delivered in full" means to
     * a customer. An attempt's own {@code attempted} figure answers a different question - whether
     * the customer took what was brought - and using it here would call an order DELIVERED when a
     * driver took 10% of it and the customer accepted all ten.
     *
     * <p>The outcome is still consulted for the one thing amounts cannot express: <em>why</em>
     * nothing arrived. Zero delivered is a refusal, a failure or an unattempted stop, and those are
     * three different conversations.
     */
    private static OrderFulfillmentStatus fromQuantities(List<OrderDelivery> attempts, PlannableOrder demand,
            DeliveryResult latestResult) {
        OrderAmounts delivered = attempts.stream()
                .map(attempt -> attempt.quantities())
                .filter(DeliveryQuantities::isRecorded)
                .map(DeliveryQuantities::delivered)
                .reduce(OrderAmounts.NONE, OrderAmounts::plus);

        if (delivered.isZero()) {
            // Nothing arrived. Which kind of nothing is the outcome's answer, not the amount's.
            return translate(latestResult);
        }
        return delivered.covers(OrderAmounts.wholeOf(demand))
                ? OrderFulfillmentStatus.DELIVERED
                : OrderFulfillmentStatus.PARTIALLY_DELIVERED;
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
