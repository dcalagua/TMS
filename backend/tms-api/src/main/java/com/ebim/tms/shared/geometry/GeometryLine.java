package com.ebim.tms.shared.geometry;

import java.math.BigDecimal;

/**
 * What load geometry needs to know about one order line, and nothing else: its count, its unit
 * weight (for a crush limit), its physical block and its declared (naive) volume. The input of
 * {@link OrderGeometry}, so an order line written by {@code orders} and the same line re-stacked by
 * {@code planning} for a vehicle go through one arithmetic.
 *
 * @param block        the physical block, or {@code null} when the line has none
 * @param lineVolumeM3 {@code quantity x unitVolume}, or {@code null} when the line states no volume
 */
public record GeometryLine(BigDecimal quantity, String uom, BigDecimal unitWeightKg, PhysicalBlock block,
        BigDecimal lineVolumeM3) {
}
