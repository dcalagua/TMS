package com.ebim.tms.orders.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The one implementation of the order totals precedence rule (migration V17,
 * {@code docs/domain/ORDER_TOTALS_V1.md}). Every write path - the manual API, the bulk import,
 * and any future integration - resolves totals through {@link #resolve} and nowhere else.
 *
 * <h2>The rule</h2>
 *
 * <p>Two inputs can describe an order's capacity: the lines, and what the sender
 * <em>declares</em> ({@link DeclaredTotals}). They are not equal partners.
 *
 * <ul>
 *   <li><b>Lines win wherever they speak.</b> If the order has at least one line, the result is
 *       {@link TotalsSource#CALCULATED} and each measure is the sum over the lines.</li>
 *   <li><b>A declared value fills a measure the lines are silent about.</b> A line set where no
 *       line carries a unit weight says nothing about weight - the sum is zero because nothing
 *       was known, not because the order weighs nothing. If the sender declared a weight, that
 *       is the only real figure available and it is used. This is per measure: an order can
 *       legitimately take its weight from the lines and its pallet count from the declaration.</li>
 *   <li><b>Where both speak, they must agree.</b> A declared value that contradicts what the
 *       lines add up to is a data error - most often a unit-of-measure or a per-unit/per-case
 *       mistake - and {@link #mismatches} reports it so the caller can reject the write instead
 *       of silently preferring one number. The comparison allows {@value #TOLERANCE_PERCENT}%,
 *       which absorbs the rounding of three- and four-decimal unit figures multiplied across
 *       many lines without absorbing a genuine discrepancy.</li>
 *   <li><b>With no lines at all</b> the result is {@link TotalsSource#DECLARED}: the declared
 *       figures, or zero for anything the sender left out. This is the case an inbound
 *       integration produces constantly - "one order, 1,200 kg, 2 pallets", no line detail -
 *       and the case V10 had no room for.</li>
 * </ul>
 *
 * <p>What is deliberately <em>not</em> a rule: no caller may ever supply the effective totals
 * directly. The browser sends lines and, at most, declarations; the numbers planning reads are
 * always produced here.
 */
public record OrderTotals(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets, TotalsSource source) {

    /** Relative tolerance, in percent, allowed between a declared figure and the calculated one. */
    public static final BigDecimal TOLERANCE_PERCENT = new BigDecimal("1");

    private static final BigDecimal TOLERANCE_FRACTION =
            TOLERANCE_PERCENT.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);

    /** The measure a {@link Mismatch} is about, so a caller can name the offending column. */
    public enum Measure {
        WEIGHT_KG,
        VOLUME_M3,
        PALLETS
    }

    /**
     * One declared figure that contradicts the lines beyond {@link #TOLERANCE_PERCENT}. Carries
     * both numbers so the message an operator reads can show them rather than only asserting
     * that something is wrong.
     */
    public record Mismatch(Measure measure, BigDecimal declared, BigDecimal calculated) {
    }

    /**
     * Applies the rule. Never throws on a contradiction - {@link #mismatches} is where a caller
     * asks whether the inputs were consistent, so that the manual API can turn one into a 400
     * and the bulk import can turn it into a row-level error in a preview report without either
     * of them driving control flow through an exception.
     */
    public static OrderTotals resolve(List<OrderLineInput> lines, DeclaredTotals declared) {
        DeclaredTotals safeDeclared = declared == null ? DeclaredTotals.none() : declared;
        if (lines.isEmpty()) {
            return new OrderTotals(orZero(safeDeclared.weightKg()), orZero(safeDeclared.volumeM3()),
                    orZero(safeDeclared.pallets()), TotalsSource.DECLARED);
        }
        return new OrderTotals(
                effective(sumWeight(lines), safeDeclared.weightKg()),
                effective(sumVolume(lines), safeDeclared.volumeM3()),
                effective(sumPallets(lines), safeDeclared.pallets()),
                TotalsSource.CALCULATED);
    }

    /**
     * Every declared figure that contradicts the lines. Empty when nothing was declared, when
     * the order has no lines (nothing to contradict), or when every declaration agrees with the
     * lines within tolerance.
     */
    public static List<Mismatch> mismatches(List<OrderLineInput> lines, DeclaredTotals declared) {
        DeclaredTotals safeDeclared = declared == null ? DeclaredTotals.none() : declared;
        if (lines.isEmpty() || safeDeclared.isEmpty()) {
            return List.of();
        }
        List<Mismatch> found = new ArrayList<>();
        addIfDisagreeing(found, Measure.WEIGHT_KG, sumWeight(lines), safeDeclared.weightKg());
        addIfDisagreeing(found, Measure.VOLUME_M3, sumVolume(lines), safeDeclared.volumeM3());
        addIfDisagreeing(found, Measure.PALLETS, sumPallets(lines), safeDeclared.pallets());
        return List.copyOf(found);
    }

    /**
     * The sum over the lines, or {@code null} when not a single line carried this measure - the
     * distinction {@link #effective} needs and a plain {@code BigDecimal.ZERO} accumulator would
     * destroy.
     */
    private static BigDecimal sumWeight(List<OrderLineInput> lines) {
        return sum(lines.stream().map(OrderLineInput::lineWeightKg).toList());
    }

    private static BigDecimal sumVolume(List<OrderLineInput> lines) {
        return sum(lines.stream().map(OrderLineInput::lineVolumeM3).toList());
    }

    private static BigDecimal sumPallets(List<OrderLineInput> lines) {
        return sum(lines.stream().map(OrderLineInput::palletQuantity).toList());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = null;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total == null ? value : total.add(value);
            }
        }
        return total;
    }

    /** Lines where they speak, the declaration where they do not, zero where neither does. */
    private static BigDecimal effective(BigDecimal calculated, BigDecimal declared) {
        if (calculated != null) {
            return calculated;
        }
        return orZero(declared);
    }

    private static void addIfDisagreeing(
            List<Mismatch> found, Measure measure, BigDecimal calculated, BigDecimal declared) {
        if (calculated == null || declared == null) {
            return;
        }
        BigDecimal tolerance = calculated.abs().multiply(TOLERANCE_FRACTION);
        if (declared.subtract(calculated).abs().compareTo(tolerance) > 0) {
            found.add(new Mismatch(measure, declared, calculated));
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
