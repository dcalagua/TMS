package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way {@code planning} reads orders and moves them through the two planning-owned states
 * of the order lifecycle, without depending on {@code com.ebim.tms.orders} (rule 10 in
 * {@code docs/database/DATA_MODEL.md} section 13 - the pattern {@link OriginLookupPort}
 * established).
 *
 * <p>Unlike {@code OriginLookupPort}, this port has a write side, so its implementation is not a
 * repository translation in {@code orders.infrastructure} but a use case in
 * {@code orders.application} ({@code OrderPlanningService}): the legality of
 * {@code READY_FOR_PLANNING → PLANNED} is an order rule and stays in the module that owns the
 * lifecycle. Every method here participates in the caller's transaction, which is what makes
 * "move an order between trips" atomic across both modules.
 */
public interface OrderPlanningPort {

    /**
     * The eligible-order search: orders that may still be assigned - company-scoped, paginated,
     * and never returning lines. An order is assignable only while it is
     * {@code READY_FOR_PLANNING}; assignment moves it to {@code PLANNED}, so this method never
     * returns an order that is already on a trip.
     */
    PageResponse<PlannableOrder> searchAssignable(PlannableOrderQuery query, PageQuery pageQuery);

    /**
     * Resolves one order for a <em>new</em> assignment, refusing one that belongs to another
     * company or is not {@code READY_FOR_PLANNING} - the write-side counterpart of
     * {@link OriginLookupPort#findActiveInCompany}.
     */
    Optional<PlannableOrder> findAssignable(UUID orderId, UUID companyId);

    /**
     * Resolves every id in {@code ids} that belongs to {@code companyId}, whatever its status, in
     * one batched call - for read-only display of orders already assigned to a trip, never for
     * validating a new assignment. One call per page, not one per row.
     */
    Map<UUID, PlannableOrder> findAllInCompany(Set<UUID> ids, UUID companyId);

    /**
     * {@code READY_FOR_PLANNING → PLANNED}, called by planning when an assignment is created.
     * Refuses any other source status, so a double assignment cannot slip through even if the
     * assignment invariant were ever weakened.
     */
    void markPlanned(UUID orderId, UUID companyId);

    /**
     * {@code PLANNED → READY_FOR_PLANNING}, called when an assignment is closed and the order
     * returns to the pool. This is the "unassign" semantics {@code OrderService.cancel}'s
     * message ("unassign it from its trip first") deferred to planning - see
     * {@code docs/overnight/09_ORDERS.md} section 8, point 2.
     */
    void releaseFromPlanning(UUID orderId, UUID companyId);

    /**
     * The order's vehicle has left the dock: {@code PLANNED -> IN_EXECUTION}.
     *
     * <p>Called by {@code TripExecutionService.dispatch} for every order the departing trip
     * carries. Idempotent - a retried dispatch moves nothing twice - and silent about orders that
     * are already further along, so a replay cannot drag a closed-out order backwards.
     *
     * <p>Planning reports the <em>fact</em> and orders decides what it means for the lifecycle.
     * That is the same direction {@code markPlanned} already runs in, and it is why planning does
     * not simply write a status: which state a departure puts an order into is an order rule.
     */
    void markInExecution(UUID orderId, UUID companyId);

    /**
     * The trip carrying this order has been closed out, and this is how the order ended.
     *
     * <p>Called by {@code TripExecutionService.complete}, and again by
     * {@code TripDeliveryService.record} for every correction keyed after completion - the window
     * stays open on purpose, because the paperwork comes back later. Both callers pass the
     * fulfilment derived from {@code tms.order_delivery} at that moment, so the order's state is
     * recomputed from the fact rather than remembered alongside it and cannot drift from it.
     *
     * <p>The mapping from fulfilment to lifecycle state belongs to the orders module and is
     * asserted there: only {@link OrderFulfillmentStatus#DELIVERED} closes an order as delivered,
     * a partial closes it as partially delivered, and everything else - refused, failed, never
     * attempted, and nothing recorded at all - closes it as failed, which is both the honest
     * reading and the safe one, because failed is reopenable and delivered is not.
     *
     * <p>Idempotent. An order that is not in execution is left alone rather than refused: a
     * completion replayed after somebody reopened and replanned the order must not undo that.
     */
    void closeOut(UUID orderId, UUID companyId, OrderFulfillmentStatus fulfillment);

    /**
     * How many of the orders serviced between {@code from} and {@code to} (both inclusive) are on a
     * shipment and how many are not - the KPI report's planned-versus-unplanned figure, counted in
     * one grouped query.
     *
     * <p>A read, on a port whose other read methods resolve orders one page or one id at a time.
     * It belongs here rather than on a port of its own for the reason this one exists at all: it is
     * planning asking about orders, the lifecycle it is counting is
     * {@code docs/domain/ORDER_LIFECYCLE_V1.md}'s, and a second port with a single method would
     * give the same conversation two doors.
     *
     * <p>Ranged over {@code service_date} - the day the customer is owed the goods - and never over
     * {@code created_at}. The report's other half is ranged over the shipments' planning date, and
     * the whole point of putting the two side by side is that they are about the same operating
     * days. Counting orders by when they were typed would compare a week's plan against whatever
     * happened to be entered that week.
     */
    OrderBacklogTotals backlogTotals(UUID companyId, LocalDate from, LocalDate to);
}
