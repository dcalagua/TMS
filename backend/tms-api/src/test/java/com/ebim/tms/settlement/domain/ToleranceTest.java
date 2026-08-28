package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How far an invoice may differ before a person has to look (migration V46).
 *
 * <p>Money, so every comparison uses {@code compareTo} and never {@code equals}: {@code 30.0} and
 * {@code 30.00} are one amount, and a tolerance that said otherwise would pass or fail invoices on
 * the scale somebody happened to type.
 */
class ToleranceTest {

    @Test
    @DisplayName("no tolerance configured admits nothing but an exact match")
    void noneIsStrict() {
        assertThat(Tolerance.NONE.covers(new BigDecimal("100"), new BigDecimal("100"))).isTrue();
        assertThat(Tolerance.NONE.covers(new BigDecimal("100"), new BigDecimal("100.01"))).isFalse();
    }

    @Test
    @DisplayName("scale does not decide a verdict: 30.0 and 30.00 are one amount")
    void scaleIsIrrelevant() {
        Tolerance tolerance = new Tolerance(new BigDecimal("30.00"), null);

        assertThat(tolerance.covers(new BigDecimal("100"), new BigDecimal("130.0"))).isTrue();
        assertThat(tolerance.covers(new BigDecimal("100.000"), new BigDecimal("130"))).isTrue();
    }

    /**
     * Either bound, not both. Without an absolute floor, 3% of a small invoice is pennies and every
     * rounding difference becomes a queue nobody reads; without a percentage, a flat bound on a
     * large invoice is noise.
     */
    @Test
    @DisplayName("either bound admits the difference - they are not both required")
    void eitherBoundIsEnough() {
        Tolerance both = new Tolerance(new BigDecimal("5"), new BigDecimal("1"));

        // 4 is inside the absolute bound and outside 1%.
        assertThat(both.covers(new BigDecimal("100"), new BigDecimal("104"))).isTrue();
        // 8 is outside the absolute bound and inside 1% of 1000.
        assertThat(both.covers(new BigDecimal("1000"), new BigDecimal("1008"))).isTrue();
        // 20 is outside both.
        assertThat(both.covers(new BigDecimal("100"), new BigDecimal("120"))).isFalse();
    }

    /**
     * An undercharge is judged exactly like an overcharge. A carrier billing less than agreed is as
     * much a sign the two systems disagree, and an audit that only looked upward would miss every
     * case where TMS is the one that is wrong.
     */
    @Test
    @DisplayName("an undercharge is measured the same way as an overcharge")
    void differenceIsAbsolute() {
        Tolerance tolerance = new Tolerance(new BigDecimal("10"), null);

        assertThat(tolerance.covers(new BigDecimal("100"), new BigDecimal("110"))).isTrue();
        assertThat(tolerance.covers(new BigDecimal("100"), new BigDecimal("90"))).isTrue();
        assertThat(tolerance.covers(new BigDecimal("100"), new BigDecimal("80"))).isFalse();
    }

    /** Nothing to compare is never "within tolerance" - it is UNMATCHABLE. */
    @Test
    @DisplayName("a missing expected figure is never covered")
    void nullIsNeverCovered() {
        Tolerance generous = new Tolerance(new BigDecimal("1000000"), new BigDecimal("100"));

        assertThat(generous.covers(null, new BigDecimal("100"))).isFalse();
        assertThat(generous.covers(new BigDecimal("100"), null)).isFalse();
    }

    /** A percentage of zero is zero, so only an absolute bound can admit a difference from nothing. */
    @Test
    @DisplayName("a percentage cannot admit a difference from an expected zero")
    void percentageOfZero() {
        assertThat(new Tolerance(null, new BigDecimal("50")).covers(BigDecimal.ZERO, new BigDecimal("10")))
                .isFalse();
        assertThat(new Tolerance(new BigDecimal("10"), null).covers(BigDecimal.ZERO, new BigDecimal("10")))
                .isTrue();
    }

    @Test
    @DisplayName("a negative or impossible bound is refused outright")
    void boundsAreValidated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tolerance(new BigDecimal("-1"), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tolerance(null, new BigDecimal("101")));
    }

    /** The rounding mode is explicit, so a figure at the exact boundary behaves the same everywhere. */
    @Test
    @DisplayName("a difference exactly on the percentage boundary is admitted")
    void boundaryIsInclusive() {
        Tolerance threePercent = new Tolerance(null, new BigDecimal("3"));

        // 3% of 1450 is 43.50 exactly.
        assertThat(threePercent.covers(new BigDecimal("1450"), new BigDecimal("1493.50"))).isTrue();
        assertThat(threePercent.covers(new BigDecimal("1450"), new BigDecimal("1493.51"))).isFalse();
    }
}
