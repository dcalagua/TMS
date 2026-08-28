package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How far an invoice may differ from what TMS expected before a person has to look (migration V46).
 *
 * <p>A value object with no identity, so the arithmetic can be tested without a database and so a
 * match can snapshot exactly what it was judged against - a tolerance widened next month must not
 * restate why last month's invoice matched.
 *
 * <h2>Either bound, not both</h2>
 *
 * <p>A difference is within tolerance if it is within the absolute bound <b>or</b> within the
 * percentage. That is deliberate and it matters at both ends of the scale: 3% of a 40-unit invoice
 * is pennies, so without an absolute floor every rounding difference becomes a discrepancy queue
 * nobody reads; and a flat 50-unit bound on a 40,000-unit invoice is noise, so without a percentage
 * the large ones are audited by hand forever.
 *
 * <h2>Money</h2>
 *
 * <p>{@link BigDecimal} throughout, compared with {@code compareTo} and never {@code equals}, so
 * {@code 30.0} and {@code 30.00} are one amount. The percentage bound rounds
 * {@link RoundingMode#HALF_UP} to two places before comparison - explicitly, because an implicit
 * rounding mode is how a figure at the exact boundary comes out differently on two machines.
 */
public record Tolerance(BigDecimal absoluteAmount, BigDecimal percentage) {

    /**
     * No tolerance configured: every difference is a discrepancy.
     *
     * <p>The safe default, and the honest one. A company that has not said what it will accept has
     * not authorised anything, and inventing a permissive default would let invoices through on an
     * assumption nobody made.
     */
    public static final Tolerance NONE = new Tolerance(null, null);

    public Tolerance {
        if (absoluteAmount != null && absoluteAmount.signum() < 0) {
            throw new IllegalArgumentException("an absolute tolerance cannot be negative");
        }
        if (percentage != null && (percentage.signum() < 0 || percentage.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("a percentage tolerance must be between 0 and 100");
        }
    }

    /**
     * Whether {@code invoiced} is close enough to {@code expected}.
     *
     * <p>The difference is taken as an absolute value, so an <b>undercharge is treated exactly like
     * an overcharge</b>. That is not symmetry for its own sake: a carrier billing less than agreed
     * is as much a sign that the two systems disagree as one billing more, and a freight audit that
     * only looked upward would miss every case where TMS is the one that is wrong.
     */
    public boolean covers(BigDecimal expected, BigDecimal invoiced) {
        if (expected == null || invoiced == null) {
            // Nothing to compare. Never "within tolerance" - see MatchStatus.UNMATCHABLE.
            return false;
        }
        BigDecimal difference = invoiced.subtract(expected).abs();
        if (difference.signum() == 0) {
            return true;
        }
        if (absoluteAmount != null && difference.compareTo(absoluteAmount) <= 0) {
            return true;
        }
        return percentage != null && withinPercentage(expected, difference);
    }

    private boolean withinPercentage(BigDecimal expected, BigDecimal difference) {
        if (expected.signum() == 0) {
            // Any difference from zero is infinite in percentage terms. The absolute bound is the
            // only thing that can admit it, and it has already been consulted.
            return false;
        }
        BigDecimal allowed = expected.abs()
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return difference.compareTo(allowed) <= 0;
    }

    public boolean isConfigured() {
        return absoluteAmount != null || percentage != null;
    }
}
