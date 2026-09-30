package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderHold;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.OrderHoldRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.OrderHoldPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds on orders (migration V54, ADR-014 section 7): list, place, lift - and {@code orders}'
 * implementation of {@link OrderHoldPort}, the read planning asks before a dispatch.
 *
 * <p><b>A hold never moves the order's status.</b> It stops three things while it is active and
 * blocking - release ({@code OrderService.markReadyForPlanning}), planning
 * ({@code OrderPlanningService.searchAssignable} and {@code allocate}) and dispatch
 * ({@code DispatchReadiness}) - and lifting it lets all three through again with nothing to undo.
 *
 * <p><b>Placing a hold takes the order's row lock.</b> {@code OrderPlanningService.allocate} takes
 * the same lock before it checks for a hold, so a hold and an assignment racing on one order
 * serialise: either the hold lands first and the assignment is refused, or the assignment lands
 * first and the hold finds an order on a trip - which is the committed case below, not a lost write.
 *
 * <p><b>A hold on an order already on a trip unplans nothing.</b> Taking an order off a confirmed
 * shipment behind the dispatcher's back would be worse than the problem the hold reports. It
 * surfaces as the Control Tower advisory {@code ORDER_HOLD_ON_COMMITTED_TRIP} and blocks the
 * trip's dispatch until somebody lifts it or cancels and replans the trip.
 */
@Service
public class OrderHoldService implements OrderHoldPort {

    /** Terminal for a hold's purposes: there is nothing left to stop. */
    private static final Set<OrderStatus> NOT_HOLDABLE = Set.of(OrderStatus.CANCELLED, OrderStatus.DELIVERED);

    private final OrderHoldRepository holdRepository;
    private final TransportOrderRepository transportOrderRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public OrderHoldService(OrderHoldRepository holdRepository, TransportOrderRepository transportOrderRepository,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.holdRepository = holdRepository;
        this.transportOrderRepository = transportOrderRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public List<OrderHoldView> list(CompanyScope scope, UUID orderId) {
        requireOrder(scope, orderId);
        return holdRepository.findByOrderIdAndCompanyIdOrderByCreatedAtDesc(orderId, scope.companyId()).stream()
                .map(OrderHoldView::from)
                .toList();
    }

    @Transactional
    public OrderHoldView place(CompanyScope scope, UUID orderId, PlaceHoldRequest request) {
        TransportOrder order = transportOrderRepository.findByIdAndCompanyIdForUpdate(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        if (NOT_HOLDABLE.contains(order.status())) {
            throw new ConflictException("Order " + order.orderNumber() + " is " + order.status()
                    + " and there is nothing left to hold.");
        }

        OrderHold hold = OrderHold.byOperator(scope.companyId(), orderId, request.holdType(),
                blankToNull(request.reasonCode()), request.reason().trim(), request.isBlocking(),
                auditActorProvider.requireAppUserId());
        OrderHold saved = holdRepository.saveAndFlush(hold);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNumber", order.orderNumber());
        detail.put("holdId", saved.id().toString());
        detail.put("holdType", saved.holdType().name());
        detail.put("blocking", String.valueOf(saved.blocking()));
        if (saved.reasonCode() != null) {
            detail.put("reasonCode", saved.reasonCode());
        }
        if (isCommitted(order)) {
            // The case that needs a person: the order is already on a trip, and the hold unplans
            // nothing. Recorded so the trail says the hold was placed knowingly on committed work.
            detail.put("onCommittedTrip", "true");
        }
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, orderId, AuditAction.ORDER_HOLD_PLACED,
                detail);
        return OrderHoldView.from(saved);
    }

    @Transactional
    public OrderHoldView release(CompanyScope scope, UUID orderId, UUID holdId, ReleaseHoldRequest request) {
        TransportOrder order = requireOrder(scope, orderId);
        OrderHold hold = holdRepository.findByIdAndOrderIdAndCompanyId(holdId, orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Hold not found."));
        if (!hold.isActive()) {
            throw new ConflictException("This hold was already released on " + hold.releasedAt() + ".");
        }
        if (request.version() != null && request.version() != hold.version()) {
            throw new ConflictException("This hold was changed by someone else since it was loaded. Reload and try again.");
        }

        hold.releaseByOperator(request.releaseReason().trim(), auditActorProvider.requireAppUserId(),
                OffsetDateTime.now());
        OrderHold saved;
        try {
            saved = holdRepository.saveAndFlush(hold);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This hold was changed by someone else since it was loaded. Reload and try again.");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNumber", order.orderNumber());
        detail.put("holdId", saved.id().toString());
        detail.put("holdType", saved.holdType().name());
        detail.put("releaseReason", saved.releaseReason());
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, orderId, AuditAction.ORDER_HOLD_RELEASED,
                detail);
        return OrderHoldView.from(saved);
    }

    /** How many active blocking holds each order has; absent means none. One query for the whole set. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> activeBlockingCounts(Set<UUID> orderIds, UUID companyId) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return holdRepository.findActiveBlocking(orderIds, companyId).stream()
                .collect(Collectors.groupingBy(OrderHoldRepository.ActiveBlockingHold::getOrderId,
                        Collectors.counting()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, HeldOrder> activeBlockingHolds(Set<UUID> orderIds, UUID companyId) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> typesByOrder = new LinkedHashMap<>();
        for (OrderHoldRepository.ActiveBlockingHold row : holdRepository.findActiveBlocking(orderIds, companyId)) {
            typesByOrder.computeIfAbsent(row.getOrderId(), key -> new LinkedHashSet<>())
                    .add(row.getHoldType().name());
        }
        if (typesByOrder.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> numbers = new LinkedHashMap<>();
        transportOrderRepository.findByIdInAndCompanyId(typesByOrder.keySet(), companyId)
                .forEach(order -> numbers.put(order.id(), order.orderNumber()));
        Map<UUID, HeldOrder> held = new LinkedHashMap<>();
        typesByOrder.forEach((orderId, types) ->
                held.put(orderId, new HeldOrder(orderId, numbers.get(orderId), new ArrayList<>(types))));
        return held;
    }

    /** On a trip, whatever its status says: fully planned, on the road, or part of it allocated (V37). */
    static boolean isCommitted(TransportOrder order) {
        return order.status() == OrderStatus.PLANNED || order.status() == OrderStatus.IN_EXECUTION
                || !order.allocated().isZero();
    }

    private TransportOrder requireOrder(CompanyScope scope, UUID orderId) {
        return transportOrderRepository.findByIdAndCompanyId(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
