package com.ebim.tms.shared.geometry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An order's load geometry from its lines' (ADR-022 sections 4-5), for one usable height {@code H}.
 *
 * <p>Pure, like {@link LoadGeometryCalculator}. {@code orders} calls it once with the company's
 * reference height when the lines are written, which is what the V37 ledger stores; {@code planning}
 * calls it again with each candidate vehicle's own height, because how many units go in a stack
 * depends on how tall the vehicle is - 0.30 m crates are stacks of eight under 2.40 m and stacks of
 * seven under 2.20 m, and comparing the first figure with the second vehicle would refuse a load that
 * fits it.
 *
 * <p>Floor sums; heights take the maximum; lines without figures are counted (never estimated) once
 * any line has a block. The planning volume: with no measured line it is the declared total,
 * whatever its source. When the lines speak about volume it is their sum, with each measured line's
 * effective volume in place of its naive one. When the volume was <em>declared</em> (no line carries
 * one), the declaration covers lines nobody measured, so it is kept - unless every line was measured,
 * and then the sum of what they really occupy is the better answer.
 *
 * @param lines            one entry per input line, in order: {@code null} for a line without a block
 * @param planningVolumeM3 what capacity counts as the order's volume under {@code H}
 * @param floorAreaM2      square metres the measured lines' stacks cover
 * @param maxStackHeightM  the tallest stack, or {@code null} when nothing was measured
 * @param maxUnitHeightM   the tallest single unit, or {@code null} when nothing was measured
 * @param unmeasuredLines  lines without figures, counted only once any line has a block
 * @param anyBlock         whether any line has a physical block at all
 */
public record OrderGeometry(List<LineGeometry> lines, BigDecimal planningVolumeM3, BigDecimal floorAreaM2,
        BigDecimal maxStackHeightM, BigDecimal maxUnitHeightM, int unmeasuredLines, boolean anyBlock) {

    public OrderGeometry {
        lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    /** Whether at least one line was measured, so re-stacking it for another height can change anything. */
    public boolean anyMeasured() {
        return maxStackHeightM != null;
    }

    /**
     * @param totalVolumeM3 the order's declared volume total ({@code OrderTotals}), kept as the planning
     *                      volume when nothing was measured
     * @param usableHeightM the height to stack against: the reference height, or a vehicle's
     */
    public static OrderGeometry of(List<GeometryLine> inputs, BigDecimal totalVolumeM3, BigDecimal usableHeightM) {
        List<LineGeometry> lines = new ArrayList<>(inputs.size());
        BigDecimal effective = BigDecimal.ZERO;
        BigDecimal naiveOfTheRest = BigDecimal.ZERO;
        boolean linesSpeakVolume = false;
        BigDecimal floor = BigDecimal.ZERO;
        BigDecimal tallestStack = null;
        BigDecimal tallestUnit = null;
        int unmeasured = 0;
        boolean anyBlock = false;
        for (GeometryLine input : inputs) {
            LineGeometry geometry = input.block() == null
                    ? null
                    : LoadGeometryCalculator.compute(input.quantity(), input.uom(), input.unitWeightKg(),
                            input.block(), usableHeightM);
            lines.add(geometry);
            BigDecimal naive = input.lineVolumeM3();
            linesSpeakVolume |= naive != null;
            anyBlock |= geometry != null;
            if (geometry == null || !geometry.hasFigures()) {
                unmeasured++;
                naiveOfTheRest = naiveOfTheRest.add(naive == null ? BigDecimal.ZERO : naive);
                continue;
            }
            effective = effective.add(geometry.effectiveVolumeM3());
            floor = floor.add(geometry.floorAreaM2());
            tallestStack = tallestStack == null ? geometry.stackHeightM() : tallestStack.max(geometry.stackHeightM());
            tallestUnit = tallestUnit == null ? geometry.unitHeightM() : tallestUnit.max(geometry.unitHeightM());
        }
        BigDecimal total = totalVolumeM3 == null ? BigDecimal.ZERO : totalVolumeM3;
        BigDecimal planningVolume;
        if (tallestStack == null) {
            planningVolume = total;
        } else if (linesSpeakVolume || unmeasured == 0) {
            planningVolume = effective.add(naiveOfTheRest);
        } else {
            planningVolume = total;
        }
        return new OrderGeometry(lines, planningVolume, floor, tallestStack, tallestUnit,
                anyBlock ? unmeasured : 0, anyBlock);
    }
}
