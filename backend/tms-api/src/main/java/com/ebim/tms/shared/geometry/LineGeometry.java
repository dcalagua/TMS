package com.ebim.tms.shared.geometry;

import java.math.BigDecimal;

/**
 * What {@link LoadGeometryCalculator} says about one order line.
 *
 * <p>For {@link GeometryStatus#UNMEASURED} every figure is {@code null} and {@code reason} names
 * what was missing; for {@link GeometryStatus#MEASURED} and {@link GeometryStatus#DOES_NOT_FIT}
 * every figure is set.
 *
 * @param perStack          units per full stack ({@code k})
 * @param stacks            floor positions the line needs
 * @param stackHeightM      the height of a full stack (3 decimals)
 * @param lastStackHeightM  the height of the last stack, which may be incomplete (3 decimals)
 * @param effectiveVolumeM3 the cubic metres the stacks really occupy, last stack exact (4 decimals)
 * @param floorAreaM2       {@code stacks x L x W} (4 decimals)
 * @param unitHeightM       the height of one unit, carried so a vehicle lower than it is refused
 *                          as {@code ITEM_TALLER_THAN_VEHICLE} rather than as a stack too tall
 */
public record LineGeometry(
        GeometryStatus status,
        String reason,
        Integer perStack,
        Integer stacks,
        BigDecimal stackHeightM,
        BigDecimal lastStackHeightM,
        BigDecimal effectiveVolumeM3,
        BigDecimal floorAreaM2,
        BigDecimal unitHeightM) {

    static LineGeometry unmeasured(String reason) {
        return new LineGeometry(GeometryStatus.UNMEASURED, reason, null, null, null, null, null, null, null);
    }

    /** Whether the figures are usable by capacity (measured, or measured as one unit per stack). */
    public boolean hasFigures() {
        return status != GeometryStatus.UNMEASURED;
    }
}
