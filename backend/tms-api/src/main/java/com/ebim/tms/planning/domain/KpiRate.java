package com.ebim.tms.planning.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place a KPI percentage is divided, and the one place the answer to "divided by nothing"
 * is decided (see {@code docs/domain/KPIS_REPORTING_V1.md}).
 *
 * <p><b>An empty denominator is null, never zero and never a hundred.</b> That is the whole reason
 * this exists. A quarter in which no shipment ever recorded a departure has no on-time-departure
 * percentage; rendering it as 0% accuses an operation of never being punctual, and rendering it as
 * 100% congratulates it for the same absence of evidence. Both are worse than the dash a null
 * produces, because both are numbers somebody can put in a slide. The same rule
 * {@code PlanningCapacityService} applies to a zero capacity limit, and the same rule
 * {@code ControlTowerService} applies to a trip whose utilisation is unknown.
 *
 * <p>Pure, static and free of Spring: every figure on the report is a division of two counts the
 * database already produced, so this is a function a unit test can exercise without a context -
 * which is exactly what {@code KpiRateTest} does.
 *
 * <p><b>Percent, not fraction.</b> Everything here returns 0-100 (and above, where the quantity
 * genuinely can exceed its denominator - see {@link #per100}), matching what
 * {@code CapacityDimension.percentUsed} already sends the frontend and what
 * {@code format.percent} there expects.
 */
public final class KpiRate {

    /** One decimal place, as {@code PlanningCapacityService} uses: enough to rank, not enough to imply precision. */
    public static final int SCALE = 1;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private KpiRate() {
    }

    /**
     * {@code part / whole} as a percentage, or null when {@code whole} is zero - "nothing was
     * measured", which is a different statement from "nothing succeeded".
     *
     * <p>{@code part} is expected to be a subset of {@code whole} for every caller that uses this
     * (on-time departures out of measured departures, full deliveries out of recorded deliveries),
     * so the result is 0-100. Nothing enforces that: a caller passing an unrelated pair would get
     * an arithmetically correct answer to a question nobody asked, and a guard here could only turn
     * that into an exception on a read-only report.
     */
    public static BigDecimal percent(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(whole), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The complement of {@link #percent}: how much of {@code whole} is <em>not</em> {@code part},
     * as a percentage, or null when nothing was measured.
     *
     * <p>Exists so "on time" can be counted from the thing the database actually knows how to
     * count - lateness - without the caller having to remember to subtract. Every late-versus-total
     * pair in this codebase is stored as the late half ({@code countDepartedLateForDay},
     * {@code windowsMissed}), because that is the row a predicate can find.
     */
    public static BigDecimal percentComplement(long complement, long whole) {
        return percent(whole - complement, whole);
    }

    /**
     * A rate per hundred of something, where {@code part} is not a subset of {@code whole} and the
     * answer may legitimately exceed 100 - problems raised per hundred shipments, for instance,
     * since one shipment can have three.
     *
     * <p>Arithmetically identical to {@link #percent} and named separately anyway: a reader who
     * sees {@code percent(exceptions, trips)} would reasonably assume the result cannot pass 100,
     * and the day one does is not the day to find out it was never a percentage.
     */
    public static BigDecimal per100(long part, long whole) {
        return percent(part, whole);
    }

    /**
     * {@code used / capacity} as a percentage, or null when the capacity is absent or zero - the
     * aggregate form of {@code CapacityDimension.percentUsed}, over a whole range of shipments
     * rather than one.
     */
    public static BigDecimal percentOf(BigDecimal used, BigDecimal capacity) {
        if (used == null || capacity == null || capacity.signum() == 0) {
            return null;
        }
        return used.multiply(HUNDRED).divide(capacity, SCALE, RoundingMode.HALF_UP);
    }
}
