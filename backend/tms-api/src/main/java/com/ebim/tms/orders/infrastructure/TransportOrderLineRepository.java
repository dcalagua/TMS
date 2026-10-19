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

    // SUM(stacks) rides on the same grouped read (V62): the list's "Ocupa" column needs the order's
    // stack count, which lives on the lines, and a second query per page would be the N+1 again.
    @Query("SELECT l.order.id AS orderId, COUNT(l) AS lineCount, SUM(l.stacks) AS stacks FROM TransportOrderLine l "
            + "WHERE l.order.id IN :orderIds GROUP BY l.order.id")
    List<OrderLineCount> countByOrderIds(@Param("orderIds") Collection<UUID> orderIds);

    /**
     * The lines among {@code lineIds} that belong to an order of {@code companyId}, in one query -
     * for the published shipment plan's per-line delivery results (WAREHOUSE_EXECUTION_V1 §7.2).
     * The line has no company of its own; the predicate goes through its order.
     */
    @Query("SELECT l FROM TransportOrderLine l WHERE l.id IN :lineIds AND l.order.companyId = :companyId")
    List<TransportOrderLine> findInCompany(@Param("lineIds") Collection<UUID> lineIds,
            @Param("companyId") UUID companyId);

    interface OrderLineCount {
        UUID getOrderId();

        long getLineCount();

        /** Sum of the measured lines' stacks; null when no line of the order carries any. */
        Long getStacks();
    }
}
