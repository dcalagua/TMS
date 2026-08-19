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
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.PlannableOrderQuery;
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

    @Override
    @Transactional
    public void markPlanned(UUID orderId, UUID companyId) {
        TransportOrder order = require(orderId, companyId);
        if (order.status() != OrderStatus.READY_FOR_PLANNING) {
            throw new ConflictException("Order " + order.orderNumber() + " is not ready for planning (status: "
                    + order.status() + ").");
        }
        order.markPlanned(auditActorProvider.requireAppUserId());
        save(order);
    }

    @Override
    @Transactional
    public void releaseFromPlanning(UUID orderId, UUID companyId) {
        TransportOrder order = require(orderId, companyId);
        if (order.status() != OrderStatus.PLANNED) {
            throw new ConflictException("Order " + order.orderNumber() + " is not planned (status: "
                    + order.status() + "), so it cannot be released back to planning.");
        }
        order.markReadyForPlanning(auditActorProvider.requireAppUserId());
        save(order);
    }

    private TransportOrder require(UUID orderId, UUID companyId) {
        return transportOrderRepository.findByIdAndCompanyId(orderId, companyId)
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
                order.totalPallets());
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
