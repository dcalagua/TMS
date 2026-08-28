package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.DeclaredTotals;
import com.ebim.tms.orders.domain.OrderLineInput;
import com.ebim.tms.orders.domain.OrderNumbers;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.OrderTotals;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TransportOrderLineRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderFulfillmentPort;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order use cases. Takes a {@link CompanyScope}, never a company id, following every other
 * module's contract (see {@code OriginService}).
 *
 * <p>Resolves origin/destination through {@link OriginLookupPort}/{@link DestinationLookupPort}
 * rather than {@code masterdata}'s own repositories - {@code orders} must not depend on
 * {@code masterdata} directly ({@code ModuleBoundaryTest}); see those ports' class comments.
 */
@Service
public class OrderService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("orderNumber", "serviceDate", "priority", "status", "createdAt", "updatedAt");

    private final TransportOrderRepository transportOrderRepository;
    private final TransportOrderLineRepository transportOrderLineRepository;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final OrderFulfillmentPort orderFulfillmentPort;
    private final CompanySettingsPort companySettingsPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public OrderService(TransportOrderRepository transportOrderRepository,
            TransportOrderLineRepository transportOrderLineRepository, OriginLookupPort originLookupPort,
            DestinationLookupPort destinationLookupPort, OrderFulfillmentPort orderFulfillmentPort,
            CompanySettingsPort companySettingsPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.transportOrderRepository = transportOrderRepository;
        this.transportOrderLineRepository = transportOrderLineRepository;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.orderFulfillmentPort = orderFulfillmentPort;
        this.companySettingsPort = companySettingsPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderView> list(CompanyScope scope, OrderFilter filter, PageQuery pageQuery) {
        var specification = TransportOrderSpecifications.matching(scope.companyId(), filter.orderNumber(),
                filter.originId(), filter.destinationId(), filter.serviceDateFrom(), filter.serviceDateTo(),
                filter.status(), filter.priority());
        Page<TransportOrder> page = transportOrderRepository.findAll(specification, toPageable(pageQuery));
        List<TransportOrder> orders = page.getContent();

        Set<UUID> originIds = orders.stream().map(TransportOrder::originId).collect(Collectors.toSet());
        Set<UUID> destinationIds = orders.stream().map(TransportOrder::destinationId).collect(Collectors.toSet());
        Map<UUID, MasterReference> origins = originLookupPort.findAllInCompany(originIds, scope.companyId());
        Map<UUID, MasterReference> destinations = destinationLookupPort.findAllInCompany(destinationIds, scope.companyId());
        Map<UUID, Long> lineCounts = loadLineCounts(orders);
        // One call for the whole page, like the two lookups above it. What happened to the goods
        // is planning's to answer (OrderFulfillmentPort), and it is a second dimension beside
        // `status`, never a replacement for it - see OrderFulfillmentStatus.
        Map<UUID, OrderFulfillmentStatus> fulfillment = orderFulfillmentPort.fulfillmentOf(
                orders.stream().map(TransportOrder::id).collect(Collectors.toSet()), scope.companyId());

        List<OrderView> content = orders.stream()
                .map(order -> OrderView.from(order, origins.get(order.originId()), destinations.get(order.destinationId()),
                        lineCounts.getOrDefault(order.id(), 0L),
                        fulfillment.getOrDefault(order.id(), OrderFulfillmentStatus.PENDING)))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrderDetailView get(CompanyScope scope, UUID id) {
        return toDetailView(scope, find(scope, id));
    }

    @Transactional
    public OrderDetailView create(CompanyScope scope, OrderRequest request) {
        validateTimeWindow(request.requestedWindowStart(), request.requestedWindowEnd());
        MasterReference origin = requireActiveOrigin(scope, request.originId());
        MasterReference destination = requireActiveDestination(scope, request.destinationId());
        String externalSource = blankToNull(request.externalSource());
        String externalReference = blankToNull(request.externalReference());
        validateExternalPair(externalSource, externalReference);
        if (externalReference != null
                && transportOrderRepository.existsByCompanyIdAndExternalSourceAndExternalReference(
                        scope.companyId(), externalSource, externalReference)) {
            throw duplicateExternalReference(externalSource, externalReference);
        }

        List<OrderLineInput> lines = toLineInputs(request.lines());
        DeclaredTotals declared = toDeclaredTotals(request);
        requireConsistentTotals(lines, declared);

        UUID actorId = auditActorProvider.writerAppUserId();
        TransportOrder order = new TransportOrder(scope.companyId(), generateOrderNumber(scope), externalSource, externalReference,
                request.originId(), request.destinationId(), blankToNull(request.customerName()),
                blankToNull(request.customerReference()), request.serviceDate(), request.priority(),
                request.requestedWindowStart(), request.requestedWindowEnd(), actorId);
        order.applyLines(lines, declared, actorId);
        TransportOrder saved = saveOrConflict(order);
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, saved.id(), AuditAction.CREATE,
                Map.of("orderNumber", saved.orderNumber()));
        // Nothing can have been delivered against an order that did not exist a moment ago.
        return OrderDetailView.from(saved, origin, destination, OrderFulfillmentStatus.PENDING);
    }

    @Transactional
    public OrderDetailView update(CompanyScope scope, UUID id, OrderRequest request) {
        TransportOrder order = find(scope, id);
        requireEditable(order);
        requireCurrentVersion(order, request.version());
        validateTimeWindow(request.requestedWindowStart(), request.requestedWindowEnd());
        MasterReference origin = requireActiveOrigin(scope, request.originId());
        MasterReference destination = requireActiveDestination(scope, request.destinationId());
        String externalSource = blankToNull(request.externalSource());
        String externalReference = blankToNull(request.externalReference());
        validateExternalPair(externalSource, externalReference);
        if (externalReference != null
                && transportOrderRepository.existsByCompanyIdAndExternalSourceAndExternalReferenceAndIdNot(
                        scope.companyId(), externalSource, externalReference, id)) {
            throw duplicateExternalReference(externalSource, externalReference);
        }

        List<OrderLineInput> lines = toLineInputs(request.lines());
        DeclaredTotals declared = toDeclaredTotals(request);
        requireConsistentTotals(lines, declared);

        UUID actorId = auditActorProvider.writerAppUserId();
        order.applyChanges(externalSource, externalReference, request.originId(), request.destinationId(),
                blankToNull(request.customerName()), blankToNull(request.customerReference()), request.serviceDate(),
                request.priority(), request.requestedWindowStart(), request.requestedWindowEnd(), actorId);
        order.applyLines(lines, declared, actorId);
        TransportOrder saved = saveOrConflict(order);
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, saved.id(), AuditAction.UPDATE,
                Map.of("orderNumber", saved.orderNumber()));
        return OrderDetailView.from(saved, origin, destination, fulfillmentOf(scope, saved.id()));
    }

    /**
     * Promotes {@code NOT_READY} to {@code READY_FOR_PLANNING} after a completeness check. The
     * header fields (origin, destination, service date, priority) are already mandatory at write
     * time, so the only genuinely optional-until-now fact is the capacity the planner will fill a
     * vehicle with.
     *
     * <p>What that check is <em>not</em> is "the order must have lines". Since V17 an order whose
     * sender declared 1,200 kg and no line detail carries a real, plannable capacity figure (see
     * {@link OrderTotals}), and refusing it would leave the bulk import unable to produce a
     * plannable order from the most common integration payload there is. The requirement is
     * therefore stated directly: at least one of weight, volume or pallets must be known, however
     * it became known.
     */
    @Transactional
    public OrderDetailView markReadyForPlanning(CompanyScope scope, UUID id) {
        TransportOrder order = find(scope, id);
        if (order.status() != OrderStatus.NOT_READY) {
            throw new ConflictException(
                    "Only a not-ready order can be marked ready for planning (current status: " + order.status() + ").");
        }
        if (order.totalWeightKg().signum() == 0 && order.totalVolumeM3().signum() == 0 && order.totalPallets().signum() == 0) {
            throw new ConflictException("An order needs at least one of weight, volume or pallets - from its lines or "
                    + "declared on the header - before it can be marked ready for planning.");
        }

        order.markReadyForPlanning(auditActorProvider.writerAppUserId());
        return toDetailView(scope, saveOrConflict(order));
    }

    /**
     * Cancels an order. {@code NOT_READY}/{@code READY_FOR_PLANNING} may always be cancelled;
     * {@code PLANNED} refuses directly (planning must remove it from its trip first, which
     * {@code OrderPlanningService.releaseAllocation} does as part of
     * {@code TripService.removeOrder}); {@code CANCELLED} refuses as already-final.
     *
     * <p>Since V36 two more refusals, both taken from {@link OrderStatus}'s transition table rather
     * than restated as conditions here. {@code IN_EXECUTION} refuses because the goods are on a
     * moving vehicle and "this order did not happen" has stopped being a true statement - the same
     * reasoning that denies a departed trip a move to cancelled. {@code DELIVERED} refuses because
     * it already happened. A shortfall - {@code PARTIALLY_DELIVERED} or {@code DELIVERY_FAILED} -
     * <em>may</em> be cancelled: giving up on a redelivery is a real business decision and this is
     * where it is recorded.
     */
    @Transactional
    public OrderDetailView cancel(CompanyScope scope, UUID id, String reason) {
        TransportOrder order = find(scope, id);
        if (order.status() == OrderStatus.CANCELLED) {
            throw new ConflictException("This order is already cancelled.");
        }
        if (order.status() == OrderStatus.PLANNED) {
            throw new ConflictException("A planned order cannot be cancelled directly; unassign it from its trip first.");
        }
        if (order.status() == OrderStatus.IN_EXECUTION) {
            throw new ConflictException("Order " + order.orderNumber() + " is on a vehicle that has already departed "
                    + "and cannot be cancelled. Record what happened at the stop instead.");
        }
        if (order.status() == OrderStatus.DELIVERED) {
            throw new ConflictException("Order " + order.orderNumber() + " has been delivered and cannot be cancelled.");
        }

        order.cancel(blankToNull(reason), auditActorProvider.writerAppUserId());
        TransportOrder saved = saveOrConflict(order);
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, saved.id(), AuditAction.CANCEL,
                Map.of("orderNumber", saved.orderNumber()));
        return toDetailView(scope, saved);
    }

    /**
     * Puts an order that came back short into the plannable pool for another attempt:
     * {@code PARTIALLY_DELIVERED | DELIVERY_FAILED -> READY_FOR_PLANNING}.
     *
     * <p><b>This is the transition that makes redelivery possible at all.</b> Before V36 an order
     * whose delivery was refused stayed {@code PLANNED} forever - not plannable, so it never
     * returned to the pool; not cancellable, because cancel refuses a planned order; not
     * deliverable, because its trip was closed. A customer waiting for a second attempt was
     * invisible to the system that owed them.
     *
     * <p><b>Why a person does this and not the close-out.</b> A redelivery costs a truck. A system
     * that silently re-queued every refusal would plan work nobody agreed to pay for, so the
     * decision is explicit, permissioned and audited with the status it was taken from - which is
     * also the record of <em>why</em> a second attempt was needed.
     *
     * <p>{@code DELIVERED} is refused: there is nothing left to deliver. {@code CANCELLED} is
     * refused: it is terminal. Both refusals come from {@link OrderStatus#isReopenable()}, so the
     * rule is stated once.
     *
     * <p>The order keeps its delivery rows. They are the history of the first attempt, and the
     * fulfilment view goes on reporting the most recent one - which is what lets "this has failed
     * twice" be answered at all.
     */
    @Transactional
    public OrderDetailView reopenForPlanning(CompanyScope scope, UUID id, String reason) {
        TransportOrder order = find(scope, id);
        if (!order.status().isReopenable()) {
            throw new ConflictException("Only an order that came back short can be reopened for planning "
                    + "(current status: " + order.status() + ").");
        }

        OrderStatus reopenedFrom = order.status();
        order.reopenForPlanning(auditActorProvider.writerAppUserId());
        TransportOrder saved = saveOrConflict(order);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNumber", saved.orderNumber());
        detail.put("reopenedFrom", reopenedFrom.name());
        String note = blankToNull(reason);
        if (note != null) {
            detail.put("reason", note);
        }
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, saved.id(), AuditAction.ORDER_REOPENED, detail);
        return toDetailView(scope, saved);
    }

    private OrderDetailView toDetailView(CompanyScope scope, TransportOrder order) {
        MasterReference origin = originLookupPort.findAllInCompany(Set.of(order.originId()), scope.companyId())
                .get(order.originId());
        MasterReference destination = destinationLookupPort
                .findAllInCompany(Set.of(order.destinationId()), scope.companyId()).get(order.destinationId());
        return OrderDetailView.from(order, origin, destination, fulfillmentOf(scope, order.id()));
    }

    /**
     * One order's fulfilment state. The batch port asked for a single id rather than a second
     * per-order method on it: a detail screen is one row, and adding a singular overload would be
     * the thing the next list screen reached for by mistake.
     */
    private OrderFulfillmentStatus fulfillmentOf(CompanyScope scope, UUID orderId) {
        return orderFulfillmentPort.fulfillmentOf(Set.of(orderId), scope.companyId())
                .getOrDefault(orderId, OrderFulfillmentStatus.PENDING);
    }

    private TransportOrder find(CompanyScope scope, UUID id) {
        return transportOrderRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
    }

    private static void requireEditable(TransportOrder order) {
        if (order.status() != OrderStatus.NOT_READY && order.status() != OrderStatus.READY_FOR_PLANNING) {
            throw new ConflictException("This order can no longer be edited (status: " + order.status() + ").");
        }
    }

    private static void requireCurrentVersion(TransportOrder order, Long requestVersion) {
        if (requestVersion == null) {
            throw new InvalidRequestException("version is required to update an order.");
        }
        if (requestVersion != order.version()) {
            throw new ConflictException("This order was changed by someone else since it was loaded. Reload and try again.");
        }
    }

    private MasterReference requireActiveOrigin(CompanyScope scope, UUID originId) {
        return originLookupPort.findActiveInCompany(originId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("originId does not reference an active origin in this company."));
    }

    private MasterReference requireActiveDestination(CompanyScope scope, UUID destinationId) {
        return destinationLookupPort.findActiveInCompany(destinationId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "destinationId does not reference an active destination in this company."));
    }

    private static void validateTimeWindow(LocalTime start, LocalTime end) {
        if ((start == null) != (end == null)) {
            throw new InvalidRequestException("requestedWindowStart and requestedWindowEnd must both be provided or both left blank.");
        }
        if (start != null && !start.isBefore(end)) {
            throw new InvalidRequestException("requestedWindowStart must be earlier than requestedWindowEnd.");
        }
    }

    private static void validateExternalPair(String externalSource, String externalReference) {
        if (externalReference != null && externalSource == null) {
            throw new InvalidRequestException("externalSource is required whenever externalReference is provided.");
        }
    }

    private static DeclaredTotals toDeclaredTotals(OrderRequest request) {
        return new DeclaredTotals(request.declaredWeightKg(), request.declaredVolumeM3(), request.declaredPallets());
    }

    /**
     * Refuses a declaration that contradicts the lines. A 400 rather than a silent preference for
     * one of the two numbers: when a sender says 1,200 kg and the lines add up to 120, one of them
     * is wrong, and guessing which would put a fabricated figure in front of a planner. See
     * {@link OrderTotals} for the tolerance and {@code docs/domain/ORDER_TOTALS_V1.md} for the rule.
     */
    private static void requireConsistentTotals(List<OrderLineInput> lines, DeclaredTotals declared) {
        List<OrderTotals.Mismatch> mismatches = OrderTotals.mismatches(lines, declared);
        if (mismatches.isEmpty()) {
            return;
        }
        String detail = mismatches.stream()
                .map(mismatch -> describeMeasure(mismatch.measure()) + " declared as " + plain(mismatch.declared())
                        + " but the lines add up to " + plain(mismatch.calculated()))
                .collect(Collectors.joining("; "));
        throw new InvalidRequestException("The declared totals do not match the lines (" + detail
                + "). Correct the lines or the declared figures, or leave the declared figures blank.");
    }

    private static String describeMeasure(OrderTotals.Measure measure) {
        return switch (measure) {
            case WEIGHT_KG -> "weight (kg)";
            case VOLUME_M3 -> "volume (m3)";
            case PALLETS -> "pallets";
        };
    }

    /** {@code toPlainString}, so a scaled BigDecimal never reaches an operator as {@code 1.2E+3}. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static List<OrderLineInput> toLineInputs(List<OrderRequest.OrderLineRequest> lines) {
        return lines.stream()
                .map(line -> new OrderLineInput(line.materialCode().trim(), line.materialDescription().trim(),
                        line.quantity(), line.uom().trim().toUpperCase(Locale.ROOT), line.unitWeightKg(),
                        line.unitVolumeM3(), line.palletQuantity()))
                .toList();
    }

    /**
     * The prefix is the company's ({@code tms.company_settings.order_number_prefix}, migration V34);
     * the digits are the installation-wide sequence, which is what makes the value unique whatever
     * any tenant chooses to call its documents.
     */
    private String generateOrderNumber(CompanyScope scope) {
        return OrderNumbers.format(
                companySettingsPort.settingsOf(scope.companyId()).orderNumberPrefix(),
                transportOrderRepository.nextOrderNumberValue());
    }

    /** One batched {@code GROUP BY} query for the whole page, never one COUNT per order row. */
    private Map<UUID, Long> loadLineCounts(List<TransportOrder> orders) {
        Map<UUID, Long> counts = new HashMap<>();
        if (orders.isEmpty()) {
            return counts;
        }
        Set<UUID> orderIds = orders.stream().map(TransportOrder::id).collect(Collectors.toSet());
        for (TransportOrderLineRepository.OrderLineCount count : transportOrderLineRepository.countByOrderIds(orderIds)) {
            counts.put(count.getOrderId(), count.getLineCount());
        }
        return counts;
    }

    /**
     * Saves and translates a concurrency or uniqueness failure into the caller-facing conflict.
     * The specific "duplicate external reference" message comes from the pre-check in
     * create/update; this catch is only the backstop for a race between two concurrent requests.
     */
    private TransportOrder saveOrConflict(TransportOrder order) {
        try {
            return transportOrderRepository.saveAndFlush(order);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This order was changed by someone else since it was loaded. Reload and try again.");
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("This order could not be saved due to a conflicting value.");
        }
    }

    private static ConflictException duplicateExternalReference(String externalSource, String externalReference) {
        return new ConflictException("An order with externalSource '" + externalSource + "' and externalReference '"
                + externalReference + "' already exists in this company.");
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.DESC, "serviceDate")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
