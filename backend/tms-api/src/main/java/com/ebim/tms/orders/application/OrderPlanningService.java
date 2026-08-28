package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.OrderAllocation;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderBacklogTotals;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.PlannableOrderQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The planning-facing half of the order lifecycle: the only implementation of
 * {@link OrderPlanningPort}.
 *
 * <p>Deliberately a use case in {@code application}, not an adapter in {@code infrastructure}
 * like {@code OriginLookupAdapter}: this port has a write side, and the legality of
 * {@code READY_FOR_PLANNING → PLANNED → READY_FOR_PLANNING} is an <em>order</em> rule that must
 * stay in the module that owns {@code docs/domain/ORDER_LIFECYCLE_V1.md}, not leak into
 * {@code planning}. Planning decides <em>when</em> to call these; orders decides whether the
 * transition is allowed.
 *
 * <p>Every method joins the caller's transaction (Spring's default {@code REQUIRED}), which is
 * what makes planning's "move an order from trip A to trip B" atomic across both modules: if the
 * capacity check on B fails after A's assignment was closed, the order's status change rolls back
 * with it.
 */
@Service
public class OrderPlanningService implements OrderPlanningPort {

    /**
     * Sorting an eligible-order list by what a planner actually loads by: date, then the order
     * number, then the numbers that decide which truck it fits in. Same allow-list discipline as
     * {@code OrderService.SORTABLE_PROPERTIES} - a sort property reaches an ORDER BY clause.
     */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("orderNumber", "serviceDate", "priority", "totalWeightKg", "totalVolumeM3", "totalPallets");

    private final TransportOrderRepository transportOrderRepository;
    private final AuditActorProvider auditActorProvider;

    public OrderPlanningService(
            TransportOrderRepository transportOrderRepository, AuditActorProvider auditActorProvider) {
        this.transportOrderRepository = transportOrderRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PlannableOrder> searchAssignable(PlannableOrderQuery query, PageQuery pageQuery) {
        var specification = TransportOrderSpecifications.matching(query.companyId(), query.orderNumber(),
                query.originId(), query.destinationId(), query.serviceDate(), query.serviceDate(),
                OrderStatus.READY_FOR_PLANNING, null);
        Page<TransportOrder> page = transportOrderRepository.findAll(specification, toPageable(pageQuery));
        List<PlannableOrder> content = page.getContent().stream().map(OrderPlanningService::toPlannable).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlannableOrder> findAssignable(UUID orderId, UUID companyId) {
        return transportOrderRepository.findByIdAndCompanyId(orderId, companyId)
                .filter(order -> order.status() == OrderStatus.READY_FOR_PLANNING)
                .map(OrderPlanningService::toPlannable);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PlannableOrder> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, PlannableOrder> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (TransportOrder order : transportOrderRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(order.id(), toPlannable(order));
        }
        return byId;
    }

    /**
     * {@code READY_FOR_PLANNING -> PLANNED} when the whole order lands on a trip, and nothing at
     * all to the status when only part of it does (migration V37).
     *
     * <p><b>Takes the row lock before reading the allocation, and that is the point.</b> Two
     * planners splitting the same 100-pallet order at the same instant would otherwise each read
     * "0 allocated", each conclude there is room for 70, and each insert - leaving the order 140%
     * allocated with two rows that both looked valid when they were written. With the lock the
     * second transaction reads the first one's total and is refused. V37's
     * {@code ck_transport_order_not_over_allocated} is the backstop under that, for any caller that
     * ever reaches the table another way.
     */
    @Override
    @Transactional
    public OrderAllocation allocate(UUID orderId, UUID companyId, OrderAmounts amounts) {
        TransportOrder order = requireForUpdate(orderId, companyId);
        if (order.status() != OrderStatus.READY_FOR_PLANNING) {
            throw new ConflictException("Order " + order.orderNumber() + " is not ready for planning (status: "
                    + order.status() + ").");
        }
        OrderAmounts pending = order.allocation().pending();
        if (amounts.exceeds(pending)) {
            throw new ConflictException("Order " + order.orderNumber() + " has only "
                    + describe(pending) + " left to plan, which is less than this assignment asks for.");
        }
        order.allocate(amounts, auditActorProvider.requireAppUserId());
        save(order);
        return order.allocation();
    }

    /**
     * Gives an allocation back. The inverse of {@link #allocate}, under the same lock and for the
     * same reason.
     *
     * <p>Accepts an order in any state that can still hold an allocation, rather than insisting on
     * {@code PLANNED}: a part-allocated order is {@code READY_FOR_PLANNING} and removing its one
     * assignment has to work.
     */
    @Override
    @Transactional
    public OrderAllocation releaseAllocation(UUID orderId, UUID companyId, OrderAmounts amounts) {
        TransportOrder order = requireForUpdate(orderId, companyId);
        order.releaseAllocation(amounts, auditActorProvider.requireAppUserId());
        save(order);
        return order.allocation();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrderAllocation> allocationsOf(Set<UUID> orderIds, UUID companyId) {
        Map<UUID, OrderAllocation> byId = new HashMap<>();
        if (orderIds.isEmpty()) {
            return byId;
        }
        for (TransportOrder order : transportOrderRepository.findByIdInAndCompanyId(orderIds, companyId)) {
            byId.put(order.id(), order.allocation());
        }
        return byId;
    }

    /** The pending figure in a sentence a planner reads, in whichever measures the order uses. */
    private static String describe(OrderAmounts pending) {
        List<String> parts = new ArrayList<>();
        if (pending.weightKg().signum() > 0) {
            parts.add(pending.weightKg().stripTrailingZeros().toPlainString() + " kg");
        }
        if (pending.volumeM3().signum() > 0) {
            parts.add(pending.volumeM3().stripTrailingZeros().toPlainString() + " m3");
        }
        if (pending.pallets().signum() > 0) {
            parts.add(pending.pallets().stripTrailingZeros().toPlainString() + " pallets");
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    /**
     * {@code PLANNED -> IN_EXECUTION}, driven by the departure of the trip that carries the order.
     *
     * <p>Locks the row, as {@link #allocate} does and for the same reason: dispatch touches every
     * order on the trip at once, and two dispatchers racing the same shipment would otherwise both
     * read {@code PLANNED} and both write.
     *
     * <p>Silently does nothing when the order has already moved on. That is the idempotency the
     * port promises, and it has a second job: a dispatch replayed after somebody closed the trip
     * out must not drag a delivered order back onto the road.
     */
    @Override
    @Transactional
    public void markInExecution(UUID orderId, UUID companyId) {
        TransportOrder order = requireForUpdate(orderId, companyId);
        if (order.status() == OrderStatus.NOT_READY || order.status() == OrderStatus.READY_FOR_PLANNING
                || order.status() == OrderStatus.CANCELLED) {
            throw new ConflictException("Order " + order.orderNumber() + " is " + order.status()
                    + " and cannot be dispatched.");
        }
        if (order.markInExecution(auditActorProvider.requireAppUserId())) {
            save(order);
        }
    }

    /**
     * The trip closed out; this is how the order ended.
     *
     * <p>The fulfilment-to-lifecycle mapping is here, in the module that owns {@code OrderStatus},
     * and it is deliberately blunt: only a full handover closes an order as delivered. Refused,
     * failed, never attempted and <em>nothing recorded at all</em> all close it as
     * {@code DELIVERY_FAILED}, which is the honest reading of "the trip is over and we cannot show
     * the customer got it" and also the safe one - a failed order is reopenable and a delivered one
     * is not, so the mistake this makes is the recoverable mistake. A delivery keyed late then
     * corrects it, because the recording window stays open after completion and every correction
     * calls back here.
     *
     * <p>An order that is not in execution is left alone rather than refused. A completion replayed
     * after somebody reopened and replanned the order must not undo the replan, and the transition
     * table is what makes that safe: {@code READY_FOR_PLANNING} cannot reach an outcome.
     */
    @Override
    @Transactional
    public void closeOut(UUID orderId, UUID companyId, OrderFulfillmentStatus fulfillment) {
        TransportOrder order = requireForUpdate(orderId, companyId);
        OrderStatus outcome = closureFor(fulfillment);
        if (!order.status().canTransitionTo(outcome) && order.status() != outcome) {
            return;
        }
        if (order.closeOut(outcome, auditActorProvider.requireAppUserId())) {
            save(order);
        }
    }

    /**
     * What each fulfilment means for the lifecycle. Exhaustive on purpose - a new
     * {@code OrderFulfillmentStatus} must not silently fall through to a default.
     */
    static OrderStatus closureFor(OrderFulfillmentStatus fulfillment) {
        return switch (fulfillment) {
            case DELIVERED -> OrderStatus.DELIVERED;
            case PARTIALLY_DELIVERED -> OrderStatus.PARTIALLY_DELIVERED;
            case REJECTED, FAILED, NOT_ATTEMPTED, PENDING -> OrderStatus.DELIVERY_FAILED;
        };
    }

    /**
     * The planned-versus-unplanned figure, counted in one grouped query.
     *
     * <p>Read back through a map keyed on the enum rather than by position, so a state the range
     * happens to contain none of defaults to zero instead of shifting every count one place along.
     * A ninth {@code OrderStatus} would still need a line here, which is why
     * {@code OrderBacklogTotals} names every state rather than carrying a total and a residue.
     */
    @Override
    @Transactional(readOnly = true)
    public OrderBacklogTotals backlogTotals(UUID companyId, LocalDate from, LocalDate to) {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        transportOrderRepository.countByStatusForServiceDates(companyId, from, to)
                .forEach(count -> byStatus.put(count.getStatus(), count.getOrderCount()));
        return new OrderBacklogTotals(
                byStatus.getOrDefault(OrderStatus.NOT_READY, 0L),
                byStatus.getOrDefault(OrderStatus.READY_FOR_PLANNING, 0L),
                byStatus.getOrDefault(OrderStatus.PLANNED, 0L),
                byStatus.getOrDefault(OrderStatus.IN_EXECUTION, 0L),
                byStatus.getOrDefault(OrderStatus.DELIVERED, 0L),
                byStatus.getOrDefault(OrderStatus.PARTIALLY_DELIVERED, 0L)
                        + byStatus.getOrDefault(OrderStatus.DELIVERY_FAILED, 0L),
                byStatus.getOrDefault(OrderStatus.CANCELLED, 0L));
    }

    private TransportOrder require(UUID orderId, UUID companyId) {
        return transportOrderRepository.findByIdAndCompanyId(orderId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
    }

    /** {@link #require} under the row lock the execution transitions take - see the repository. */
    private TransportOrder requireForUpdate(UUID orderId, UUID companyId) {
        return transportOrderRepository.findByIdAndCompanyIdForUpdate(orderId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
    }

    /**
     * Flushed immediately, not left to the end of the transaction: planning calls this in the
     * middle of an assignment and needs a concurrent edit of the same order to surface here as a
     * 409, not as a late failure attributed to something else.
     */
    private void save(TransportOrder order) {
        try {
            transportOrderRepository.saveAndFlush(order);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException(
                    "This order was changed by someone else while it was being planned. Reload and try again.");
        }
    }

    private static PlannableOrder toPlannable(TransportOrder order) {
        return new PlannableOrder(order.id(), order.orderNumber(), order.originId(), order.destinationId(),
                order.customerName(), order.customerReference(), order.serviceDate(), order.priority().name(),
                order.requestedWindowStart(), order.requestedWindowEnd(), order.totalWeightKg(), order.totalVolumeM3(),
                order.totalPallets(), order.externalSource(), order.externalReference(), order.allocated());
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Order.asc("serviceDate"), Sort.Order.asc("orderNumber"))
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
