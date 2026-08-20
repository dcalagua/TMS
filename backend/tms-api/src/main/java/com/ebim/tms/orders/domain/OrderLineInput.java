package com.ebim.tms.orders.domain;

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
 */
public record OrderLineInput(
        String materialCode,
        String materialDescription,
        BigDecimal quantity,
        String uom,
        BigDecimal unitWeightKg,
        BigDecimal unitVolumeM3,
        BigDecimal palletQuantity) {

    /** {@code quantity * unitWeightKg}, or {@code null} when the unit weight is unknown. */
    public BigDecimal lineWeightKg() {
        return unitWeightKg == null ? null : quantity.multiply(unitWeightKg);
    }

    /** {@code quantity * unitVolumeM3}, or {@code null} when the unit volume is unknown. */
    public BigDecimal lineVolumeM3() {
        return unitVolumeM3 == null ? null : quantity.multiply(unitVolumeM3);
    }
}
