package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.OrderDelivery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The delivery outcomes recorded against a trip's stops (migration V28).
 *
 * <p>Company-scoped in every signature, for the reason {@link TransportEventRepository} gives: the
 * tenant predicate belongs in the query, and RLS is the second line rather than the first.
 *
 * <p>There is no delete. "This delivery never happened" is itself a result
 * ({@code NOT_ATTEMPTED}), not the absence of one, and migration V28 withholds the {@code DELETE}
 * grant so the point cannot be argued at the call site.
 */
public interface OrderDeliveryRepository extends JpaRepository<OrderDelivery, UUID> {

    /** One trip's deliveries - the single query behind a trip detail. */
    List<OrderDelivery> findByCompanyIdAndTripId(UUID companyId, UUID tripId);

    /**
     * The row a recording replaces, if there is one. Keyed on the stop and the order rather than on
     * the id, because that pair is the delivery's real identity - {@code uq_order_delivery_stop_order}
     * says so - and the caller addressing it by URL knows nothing else.
     */
    Optional<OrderDelivery> findByCompanyIdAndTripStopIdAndOrderId(UUID companyId, UUID tripStopId, UUID orderId);

    Optional<OrderDelivery> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The deliveries recorded for a set of orders, for the outbound shipment payload: one query for
     * a whole shipment's order list rather than one per order.
     *
     * <p>An order can in principle appear twice - assigned to a trip, taken off it, and delivered
     * on another - so the caller reduces the result rather than assuming one row per order. In
     * practice there is one.
     */
    List<OrderDelivery> findByCompanyIdAndOrderIdIn(UUID companyId, Collection<UUID> orderIds);

    // --- KPI report ------------------------------------------------------------------------

    /**
     * What was delivered on each operating day of a range, grouped by outcome - one statement
     * behind both the report's delivery-success figure and the daily series.
     *
     * <p>Dated by the <em>trip's</em> planning date and not by {@code deliveredAt}, for the reason
     * {@code TripExceptionRepository} gives about exceptions: a handover recorded at 00:20 on a
     * shipment that left the previous evening belongs to that shipment's day. Ranging on
     * {@code deliveredAt} would move it into the next day exactly when a night shift is reading the
     * report, and would silently drop {@code NOT_ATTEMPTED}, which has no delivery time at all.
     *
     * <p>Joined to {@code Trip} through an entity join on the plain id column - {@code OrderDelivery}
     * holds no association to its trip, deliberately (it is not part of that aggregate) - which is
     * the shape {@code TripOrderAssignmentRepository.countByPlanningRunIds} already uses.
     */
    @Query("""
            SELECT t.planningDate AS planningDate, d.result AS result, COUNT(d) AS deliveryCount
              FROM OrderDelivery d
              JOIN Trip t ON t.id = d.tripId
             WHERE d.companyId = :companyId
               AND t.companyId = :companyId
               AND t.planningDate BETWEEN :from AND :to
             GROUP BY t.planningDate, d.result
             ORDER BY t.planningDate
            """)
    List<DeliveryDailyCount> countDailyByResultForRange(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface DeliveryDailyCount {
        LocalDate getPlanningDate();

        DeliveryResult getResult();

        long getDeliveryCount();
    }
}
