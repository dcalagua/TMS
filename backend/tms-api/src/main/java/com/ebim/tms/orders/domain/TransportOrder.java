package com.ebim.tms.orders.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A transport order header (migration V10): the first operational, non-master entity in TMS.
 * See {@code docs/domain/ORDER_LIFECYCLE_V1.md} for the full status lifecycle and
 * {@code docs/database/DATA_MODEL.md} section 12 for the schema decisions.
 *
 * <p>{@code lines} is owned here, exactly like {@code Route} owns {@code stops}: every mutation
 * goes through {@link #applyLines(List, UUID)}, which - unlike {@code Route.replaceStops} - does
 * not diff against what is persisted, because an order line has no natural key that survives an
 * edit (see {@link TransportOrderLine}'s class comment). {@code totalWeightKg}/{@code
 * totalVolumeM3}/{@code totalPallets} are a transactional snapshot re-resolved by the same
 * method, in the same transaction as every line change - see the migration's comment on why
 * persisting them is safe (the backend, specifically this class, is the only writer).
 *
 * <p>Since V17 those totals are not necessarily the line sums: {@link OrderTotals} decides
 * between the lines and the sender's {@link DeclaredTotals}, and {@code totalsSource} records
 * which strategy produced them. See {@code docs/domain/ORDER_TOTALS_V1.md}.
 */
@Entity
@Table(name = "transport_order")
public class TransportOrder {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "order_number", updatable = false, nullable = false)
    private String orderNumber;

    @Column(name = "external_source")
    private String externalSource;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "origin_id", nullable = false)
    private UUID originId;

    @Column(name = "destination_id", nullable = false)
    private UUID destinationId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_reference")
    private String customerReference;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private OrderPriority priority;

    @Column(name = "requested_window_start")
    private LocalTime requestedWindowStart;

    @Column(name = "requested_window_end")
    private LocalTime requestedWindowEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "total_weight_kg", nullable = false, precision = 14, scale = 3)
    private BigDecimal totalWeightKg = BigDecimal.ZERO;

    @Column(name = "total_volume_m3", nullable = false, precision = 14, scale = 4)
    private BigDecimal totalVolumeM3 = BigDecimal.ZERO;

    @Column(name = "total_pallets", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPallets = BigDecimal.ZERO;

    // The declared (asserted) figures, kept next to the effective totals rather than instead of
    // them - migration V17 and OrderTotals' class comment explain why both are persisted.
    // Nullable, and "not stated" is genuinely different from "stated as zero".
    @Column(name = "declared_weight_kg", precision = 14, scale = 3)
    private BigDecimal declaredWeightKg;

    @Column(name = "declared_volume_m3", precision = 14, scale = 4)
    private BigDecimal declaredVolumeM3;

    @Column(name = "declared_pallets", precision = 12, scale = 2)
    private BigDecimal declaredPallets;

    @Enumerated(EnumType.STRING)
    @Column(name = "totals_source", nullable = false)
    private TotalsSource totalsSource = TotalsSource.DECLARED;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TransportOrderLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected TransportOrder() {
        // JPA
    }

    public TransportOrder(UUID companyId, String orderNumber, String externalSource, String externalReference,
            UUID originId, UUID destinationId, String customerName, String customerReference, LocalDate serviceDate,
            OrderPriority priority, LocalTime requestedWindowStart, LocalTime requestedWindowEnd, UUID actorId) {
        this.companyId = companyId;
        this.orderNumber = orderNumber;
        this.externalSource = externalSource;
        this.externalReference = externalReference;
        this.originId = originId;
        this.destinationId = destinationId;
        this.customerName = customerName;
        this.customerReference = customerReference;
        this.serviceDate = serviceDate;
        this.priority = priority;
        this.requestedWindowStart = requestedWindowStart;
        this.requestedWindowEnd = requestedWindowEnd;
        this.status = OrderStatus.NOT_READY;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public String externalSource() {
        return externalSource;
    }

    public String externalReference() {
        return externalReference;
    }

    public UUID originId() {
        return originId;
    }

    public UUID destinationId() {
        return destinationId;
    }

    public String customerName() {
        return customerName;
    }

    public String customerReference() {
        return customerReference;
    }

    public LocalDate serviceDate() {
        return serviceDate;
    }

    public OrderPriority priority() {
        return priority;
    }

    public LocalTime requestedWindowStart() {
        return requestedWindowStart;
    }

    public LocalTime requestedWindowEnd() {
        return requestedWindowEnd;
    }

    public OrderStatus status() {
        return status;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public BigDecimal totalWeightKg() {
        return totalWeightKg;
    }

    public BigDecimal totalVolumeM3() {
        return totalVolumeM3;
    }

    public BigDecimal totalPallets() {
        return totalPallets;
    }

    public BigDecimal declaredWeightKg() {
        return declaredWeightKg;
    }

    public BigDecimal declaredVolumeM3() {
        return declaredVolumeM3;
    }

    public BigDecimal declaredPallets() {
        return declaredPallets;
    }

    public TotalsSource totalsSource() {
        return totalsSource;
    }

    public long version() {
        return version;
    }

    /** Ordered by line number, ascending (1..N - see {@link #applyLines}). */
    public List<TransportOrderLine> lines() {
        return lines.stream().sorted(Comparator.comparingInt(TransportOrderLine::lineNumber)).toList();
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    /**
     * Applies an edit to the header fields. Callable only while the order is editable
     * ({@code OrderService.requireEditable} checks this before calling) - resets a
     * {@link OrderStatus#READY_FOR_PLANNING} order back to {@link OrderStatus#NOT_READY}
     * unconditionally, because an edit may have invalidated the completeness
     * {@code OrderService.markReadyForPlanning} last confirmed; the caller must explicitly mark
     * it ready again. See {@code docs/domain/ORDER_LIFECYCLE_V1.md}, "Editing resets readiness".
     */
    public void applyChanges(String externalSource, String externalReference, UUID originId, UUID destinationId,
            String customerName, String customerReference, LocalDate serviceDate, OrderPriority priority,
            LocalTime requestedWindowStart, LocalTime requestedWindowEnd, UUID actorId) {
        this.externalSource = externalSource;
        this.externalReference = externalReference;
        this.originId = originId;
        this.destinationId = destinationId;
        this.customerName = customerName;
        this.customerReference = customerReference;
        this.serviceDate = serviceDate;
        this.priority = priority;
        this.requestedWindowStart = requestedWindowStart;
        this.requestedWindowEnd = requestedWindowEnd;
        this.status = OrderStatus.NOT_READY;
        this.updatedBy = actorId;
    }

    /**
     * Replaces the whole line set and re-resolves the header totals in the same pass. Unlike
     * {@code Route.replaceStops}, this does not diff against what is persisted - see
     * {@link TransportOrderLine}'s class comment for why no natural key survives an edit here.
     *
     * <p>The totals are not summed here: {@link OrderTotals#resolve} owns the precedence between
     * the lines and {@code declared}, and this method only stores what it returns. A caller that
     * cares whether the two contradicted each other asks {@link OrderTotals#mismatches} before
     * calling - {@code OrderService} turns one into a 400 and the bulk import into a row error.
     */
    public void applyLines(List<OrderLineInput> inputs, DeclaredTotals declared, UUID actorId) {
        lines.clear();
        int lineNumber = 1;
        for (OrderLineInput input : inputs) {
            lines.add(new TransportOrderLine(this, lineNumber++, input, actorId));
        }

        DeclaredTotals safeDeclared = declared == null ? DeclaredTotals.none() : declared;
        this.declaredWeightKg = safeDeclared.weightKg();
        this.declaredVolumeM3 = safeDeclared.volumeM3();
        this.declaredPallets = safeDeclared.pallets();

        OrderTotals totals = OrderTotals.resolve(inputs, safeDeclared);
        this.totalWeightKg = totals.weightKg();
        this.totalVolumeM3 = totals.volumeM3();
        this.totalPallets = totals.pallets();
        this.totalsSource = totals.source();
        this.updatedBy = actorId;
    }

    /** Legality of this transition is {@code OrderService.markReadyForPlanning}'s concern, not this entity's. */
    public void markReadyForPlanning(UUID actorId) {
        this.status = OrderStatus.READY_FOR_PLANNING;
        this.updatedBy = actorId;
    }

    /**
     * Called by {@code OrderPlanningService.markPlanned} (step 10) once planning creates an
     * assignment for this order; releasing it back to the pool reuses {@link
     * #markReadyForPlanning}. Legality of either transition is that service's concern, not this
     * entity's - see {@code docs/domain/PLANNING_MANUAL_V1.md}, "Order lifecycle interaction".
     */
    public void markPlanned(UUID actorId) {
        this.status = OrderStatus.PLANNED;
        this.updatedBy = actorId;
    }

    /** Legality of this transition is {@code OrderService.cancel}'s concern, not this entity's. */
    public void cancel(String reason, UUID actorId) {
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
        this.updatedBy = actorId;
    }

    /**
     * {@code PLANNED -> IN_EXECUTION}: the vehicle carrying this order has left the dock. Driven by
     * {@code TripExecutionService.dispatch} through {@code OrderPlanningPort.markInExecution}.
     *
     * <p>Idempotent by design, and it has to be: dispatch is retried, and a trip carrying forty
     * orders must not fail its second attempt because the first already moved half of them. An
     * order already in execution is left exactly as it is, and so is a closed-out one - a correction
     * keyed after the trip completed must not be dragged backwards by a replayed dispatch.
     *
     * @return whether this call changed anything, so the caller can audit only real moves
     */
    public boolean markInExecution(UUID actorId) {
        if (status == OrderStatus.IN_EXECUTION || status.isClosedOut()) {
            return false;
        }
        transitionTo(OrderStatus.IN_EXECUTION);
        this.updatedBy = actorId;
        return true;
    }

    /**
     * {@code IN_EXECUTION -> DELIVERED | PARTIALLY_DELIVERED | DELIVERY_FAILED}: the trip has been
     * closed out and this is what the road did with the goods. Driven by
     * {@code TripExecutionService.complete}, and again by {@code TripDeliveryService.record} for
     * every correction keyed after completion - which is why the three outcomes are mutually
     * reachable and why this can never drift from {@code tms.order_delivery}: it is recomputed from
     * those rows in the same transaction that changes them.
     *
     * <p>Idempotent for the same reason {@link #markInExecution} is. A closed-out order that is
     * reopened and planned again is out of reach here by the transition table, not by a flag:
     * {@code READY_FOR_PLANNING} and {@code PLANNED} cannot move straight to an outcome, so a late
     * correction against the <em>old</em> trip is refused rather than silently undoing the replan.
     *
     * @param outcome one of the three closed-out states
     * @return whether this call changed anything
     */
    public boolean closeOut(OrderStatus outcome, UUID actorId) {
        if (!outcome.isClosedOut()) {
            throw new IllegalArgumentException(outcome + " is not a delivery outcome.");
        }
        if (status == outcome) {
            return false;
        }
        transitionTo(outcome);
        this.updatedBy = actorId;
        return true;
    }

    /**
     * {@code PARTIALLY_DELIVERED | DELIVERY_FAILED -> READY_FOR_PLANNING}: the customer is still
     * owed something, so the order goes back into the plannable pool for another attempt.
     *
     * <p>This is the transition that makes multiple delivery attempts possible at all. Before it
     * existed a failed order stayed {@code PLANNED} forever: not plannable, not cancellable, not
     * deliverable. Legality is asserted here and refused first, with a sentence a planner can read,
     * by {@code OrderService.reopenForPlanning}.
     */
    public void reopenForPlanning(UUID actorId) {
        transitionTo(OrderStatus.READY_FOR_PLANNING);
        this.updatedBy = actorId;
    }

    /**
     * The last line of defense under the execution transitions, in the shape {@code Trip} uses:
     * the service refuses first with a message for a human, and the entity refuses again so that a
     * second caller - a future driver app, a batch job - cannot reach an illegal state by skipping
     * the service. {@code OrderStatus} is the single place the rule is written down; migration V36's
     * CHECK constraint is the backstop under both.
     *
     * <p>The older transitions ({@code applyChanges}, {@code markReadyForPlanning},
     * {@code markPlanned}, {@code cancel}) deliberately keep their existing shape, where legality is
     * the service's concern alone. Retrofitting assertions onto them is a behaviour change to paths
     * that are already covered by tests and carries no benefit this job needs.
     */
    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Order " + orderNumber + " cannot move from " + status + " to " + target + ".");
        }
        this.status = target;
    }
}
