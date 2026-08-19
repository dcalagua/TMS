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
 * totalVolumeM3}/{@code totalPallets} are a transactional snapshot recomputed by the same method,
 * in the same transaction as every line change - see the migration's comment on why persisting
 * them is safe (the backend, specifically this class, is the only writer).
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
     * Replaces the whole line set and recomputes the header totals in the same pass. Unlike
     * {@code Route.replaceStops}, this does not diff against what is persisted - see
     * {@link TransportOrderLine}'s class comment for why no natural key survives an edit here.
     */
    public void applyLines(List<OrderLineInput> inputs, UUID actorId) {
        lines.clear();
        int lineNumber = 1;
        for (OrderLineInput input : inputs) {
            lines.add(new TransportOrderLine(this, lineNumber++, input, actorId));
        }
        recomputeTotals();
        this.updatedBy = actorId;
    }

    private void recomputeTotals() {
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal pallets = BigDecimal.ZERO;
        for (TransportOrderLine line : lines) {
            if (line.lineWeightKg() != null) {
                weight = weight.add(line.lineWeightKg());
            }
            if (line.lineVolumeM3() != null) {
                volume = volume.add(line.lineVolumeM3());
            }
            if (line.palletQuantity() != null) {
                pallets = pallets.add(line.palletQuantity());
            }
        }
        this.totalWeightKg = weight;
        this.totalVolumeM3 = volume;
        this.totalPallets = pallets;
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
}
