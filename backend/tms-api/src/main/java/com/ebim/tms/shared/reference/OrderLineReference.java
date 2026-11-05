package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order as a person recording a delivery needs it (V75): which product, how many
 * were ordered, in what unit, and what one unit weighs - so a refusal can be typed per product and
 * its kilos derived rather than estimated.
 *
 * <p>Separate from {@link OrderLineSnapshot}, which compares a warehouse dispatch against the plan
 * and carries no id: a delivery line points at the order line by id (V45's
 * {@code fk_order_delivery_line_line_in_order}).
 *
 * @param unitWeightKg kilograms per unit, or null when the order did not say
 * @param unitVolumeM3 cubic metres per unit, or null when the order did not say
 */
public record OrderLineReference(UUID id, UUID orderId, int lineNumber, String materialCode,
        String materialDescription, BigDecimal quantity, String uom, BigDecimal unitWeightKg,
        BigDecimal unitVolumeM3) {
}
