package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.HoldType;
import com.ebim.tms.orders.domain.OrderHold;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Company-scoped persistence for {@link OrderHold} (migration V54). Every finder names the company,
 * including the batched ones a board uses: a hold is read by order id, and an order id comes from a
 * request.
 */
public interface OrderHoldRepository extends JpaRepository<OrderHold, UUID> {

    Optional<OrderHold> findByIdAndOrderIdAndCompanyId(UUID id, UUID orderId, UUID companyId);

    List<OrderHold> findByOrderIdAndCompanyIdOrderByCreatedAtDesc(UUID orderId, UUID companyId);

    /** One order's active holds, blocking or not - for the release gate and the detail panel. */
    @Query("""
            select h from OrderHold h
             where h.companyId = :companyId and h.orderId = :orderId and h.releasedAt is null
             order by h.createdAt
            """)
    List<OrderHold> findActive(@Param("orderId") UUID orderId, @Param("companyId") UUID companyId);

    /** Every active hold of a page of orders, in one query - the board never asks per row. */
    @Query("""
            select h from OrderHold h
             where h.companyId = :companyId and h.orderId in :orderIds and h.releasedAt is null
             order by h.createdAt
            """)
    List<OrderHold> findActiveForOrders(@Param("orderIds") Collection<UUID> orderIds,
            @Param("companyId") UUID companyId);

    /** A row per active blocking hold: its order and type - what dispatch and the Control Tower need. */
    @Query("""
            select h.orderId as orderId, h.holdType as holdType from OrderHold h
             where h.companyId = :companyId and h.orderId in :orderIds
               and h.releasedAt is null and h.blocking = true
            """)
    List<ActiveBlockingHold> findActiveBlocking(@Param("orderIds") Collection<UUID> orderIds,
            @Param("companyId") UUID companyId);

    @Query("""
            select count(h) > 0 from OrderHold h
             where h.companyId = :companyId and h.orderId = :orderId
               and h.releasedAt is null and h.blocking = true
            """)
    boolean existsActiveBlocking(@Param("orderId") UUID orderId, @Param("companyId") UUID companyId);

    interface ActiveBlockingHold {
        UUID getOrderId();

        HoldType getHoldType();
    }
}
