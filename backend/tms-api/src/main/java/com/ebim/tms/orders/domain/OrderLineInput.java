package com.ebim.tms.orders.domain;

import com.ebim.tms.shared.geometry.GeometryLine;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import java.math.BigDecimal;

/**
 * The domain-level shape of one order line, used by {@link TransportOrder#applyLines} and
 * {@link TransportOrderLine}. Deliberately not the same type as
 * {@code com.ebim.tms.orders.application.OrderRequest.OrderLineRequest}: the domain must not
 * depend on the application layer's Bean Validation-annotated request DTO, so
 * {@code OrderService} maps one to the other, the same layering every other module keeps
 * between its {@code *Request} record and its entity constructor arguments.
 *
 * <p>{@link #lineWeightKg()} and {@link #lineVolumeM3()} are the single definition of how a
 * line's contribution is derived. {@link TransportOrderLine#applyInput} persists what they
 * return and {@link OrderTotals} sums it, so the header snapshot and the line rows can never
 * disagree about the formula.
 *
 * @param physical       the unit's physical block (V59), or {@code null} when the line has none
 * @param physicalSource where {@code physical} came from; {@code null} exactly when it is
 * @param materialProfileVersion the copied profile version when the source is
 *     {@link PhysicalSource#PROFILE}; {@code null} otherwise
 */
public record OrderLineInput(
        String materialCode,
        String materialDescription,
        BigDecimal quantity,
        String uom,
        BigDecimal unitWeightKg,
        BigDecimal unitVolumeM3,
        BigDecimal palletQuantity,
        PhysicalBlock physical,
        PhysicalSource physicalSource,
        Long materialProfileVersion) {

    public OrderLineInput {
        if (physical != null && physical.isEmpty()) {
            physical = null;
        }
        if (physical == null) {
            physicalSource = null;
            materialProfileVersion = null;
        } else if (physicalSource == null) {
            throw new IllegalArgumentException("a physical block needs a source");
        }
        if (physicalSource != PhysicalSource.PROFILE) {
            materialProfileVersion = null;
        }
    }

    /** A line without a physical block - every path before V59, and the bulk import today. */
    public OrderLineInput(String materialCode, String materialDescription, BigDecimal quantity, String uom,
            BigDecimal unitWeightKg, BigDecimal unitVolumeM3, BigDecimal palletQuantity) {
        this(materialCode, materialDescription, quantity, uom, unitWeightKg, unitVolumeM3, palletQuantity, null, null,
                null);
    }

    /** The same line with another physical block. */
    public OrderLineInput withPhysical(PhysicalBlock block, PhysicalSource source, Long profileVersion) {
        return new OrderLineInput(materialCode, materialDescription, quantity, uom, unitWeightKg, unitVolumeM3,
                palletQuantity, block, source, profileVersion);
    }

    /** The same line with other unit figures. */
    public OrderLineInput withUnitFigures(BigDecimal weightKg, BigDecimal volumeM3) {
        return new OrderLineInput(materialCode, materialDescription, quantity, uom, weightKg, volumeM3, palletQuantity,
                physical, physicalSource, materialProfileVersion);
    }

    /** {@code quantity * unitWeightKg}, or {@code null} when the unit weight is unknown. */
    public BigDecimal lineWeightKg() {
        return unitWeightKg == null ? null : quantity.multiply(unitWeightKg);
    }

    /** What load geometry reads of this line ({@code OrderGeometry}). */
    public GeometryLine geometryLine() {
        return new GeometryLine(quantity, uom, unitWeightKg, physical, lineVolumeM3());
    }

    /** {@code quantity * unitVolumeM3}, or {@code null} when the unit volume is unknown. */
    public BigDecimal lineVolumeM3() {
        return unitVolumeM3 == null ? null : quantity.multiply(unitVolumeM3);
    }
}
