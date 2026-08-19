package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.TransportOrderLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code TransportOrderLine} rows are only ever written through {@code TransportOrder.applyLines}
 * (cascaded from {@link TransportOrderRepository}); this repository exists so
 * {@code OrderService} can read line counts for a whole page of orders in one query instead of
 * walking each order's lazily-loaded {@code lines} collection - the same N+1 avoidance
 * {@code RouteStopRepository.countByRouteIds} gives routes.
 */
public interface TransportOrderLineRepository extends JpaRepository<TransportOrderLine, UUID> {

    @Query("SELECT l.order.id AS orderId, COUNT(l) AS lineCount FROM TransportOrderLine l "
            + "WHERE l.order.id IN :orderIds GROUP BY l.order.id")
    List<OrderLineCount> countByOrderIds(@Param("orderIds") Collection<UUID> orderIds);

    interface OrderLineCount {
        UUID getOrderId();

        long getLineCount();
    }
}
