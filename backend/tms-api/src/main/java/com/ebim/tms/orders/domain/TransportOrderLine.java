package com.ebim.tms.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One line of a {@link TransportOrder} (migration V10): a material/SKU snapshot, quantity and
 * UOM, optional unit weight/volume, and the computed line totals those two produce.
 *
 * <p>Unlike {@code RouteStop} (updated in place, keyed by {@code destinationId}) or
 * {@code FrequencyWeeklyRule} (keyed by {@code dayOfWeek}), a line has no natural key that
 * survives an edit - two lines can legitimately share the same {@code materialCode} with
 * different quantities. {@link TransportOrder#applyLines} therefore deletes and re-creates the
 * whole set on every update rather than diffing one; see that method's comment.
 */
@Entity
@Table(name = "transport_order_line")
public class TransportOrderLine {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private TransportOrder order;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "material_code", nullable = false)
    private String materialCode;

    @Column(name = "material_description", nullable = false)
    private String materialDescription;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "uom", nullable = false)
    private String uom;

    @Column(name = "unit_weight_kg", precision = 10, scale = 3)
    private BigDecimal unitWeightKg;

    @Column(name = "unit_volume_m3", precision = 10, scale = 4)
    private BigDecimal unitVolumeM3;

    @Column(name = "line_weight_kg", precision = 14, scale = 3)
    private BigDecimal lineWeightKg;

    @Column(name = "line_volume_m3", precision = 14, scale = 4)
    private BigDecimal lineVolumeM3;

    @Column(name = "pallet_quantity", precision = 10, scale = 2)
    private BigDecimal palletQuantity;

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

    protected TransportOrderLine() {
        // JPA
    }

    TransportOrderLine(TransportOrder order, int lineNumber, OrderLineInput input, UUID actorId) {
        this.order = order;
        this.lineNumber = lineNumber;
        this.createdBy = actorId;
        applyInput(input, actorId);
    }

    public UUID id() {
        return id;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public String materialCode() {
        return materialCode;
    }

    public String materialDescription() {
        return materialDescription;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public String uom() {
        return uom;
    }

    public BigDecimal unitWeightKg() {
        return unitWeightKg;
    }

    public BigDecimal unitVolumeM3() {
        return unitVolumeM3;
    }

    public BigDecimal lineWeightKg() {
        return lineWeightKg;
    }

    public BigDecimal lineVolumeM3() {
        return lineVolumeM3;
    }

    public BigDecimal palletQuantity() {
        return palletQuantity;
    }

    /** Recomputes {@code lineWeightKg}/{@code lineVolumeM3} from {@code quantity * unit*} - never client-supplied. */
    void applyInput(OrderLineInput input, UUID actorId) {
        this.materialCode = input.materialCode();
        this.materialDescription = input.materialDescription();
        this.quantity = input.quantity();
        this.uom = input.uom();
        this.unitWeightKg = input.unitWeightKg();
        this.unitVolumeM3 = input.unitVolumeM3();
        this.lineWeightKg = input.unitWeightKg() == null ? null : input.quantity().multiply(input.unitWeightKg());
        this.lineVolumeM3 = input.unitVolumeM3() == null ? null : input.quantity().multiply(input.unitVolumeM3());
        this.palletQuantity = input.palletQuantity();
        this.updatedBy = actorId;
    }
}
