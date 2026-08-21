package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The KPI division rule - the one place the report decides what an empty denominator means.
 *
 * <p>Worth exhaustive tests for the reason {@code DepartureDelayTest} gives about lateness: a wrong
 * percentage still renders, still has a number beside it, and still looks like an answer. The one
 * that matters most is the null, because 0% and 100% are both plausible-looking lies about a
 * quarter in which nothing was measured, and both are numbers somebody will put in a slide.
 */
class KpiRateTest {

    @Nested
    @DisplayName("a percentage of nothing")
    class EmptyDenominator {

        @Test
        @DisplayName("is null and never zero")
        void percentOfNothingIsNull() {
            assertThat(KpiRate.percent(0, 0)).isNull();
        }

        @Test
        @DisplayName("is null for the complement form too, which is where an accidental 100% would come from")
        void complementOfNothingIsNull() {
            assertThat(KpiRate.percentComplement(0, 0)).isNull();
        }

        @Test
        @DisplayName("is null for a rate per hundred")
        void per100OfNothingIsNull() {
            assertThat(KpiRate.per100(7, 0)).isNull();
        }

        @Test
        @DisplayName("is null for a capacity that is absent, and for one that is a real zero")
        void percentOfNoCapacityIsNull() {
            assertThat(KpiRate.percentOf(BigDecimal.TEN, null)).isNull();
            assertThat(KpiRate.percentOf(null, BigDecimal.TEN)).isNull();
            assertThat(KpiRate.percentOf(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        }
    }

    @Nested
    @DisplayName("a percentage of something")
    class Measured {

        @Test
        @DisplayName("is the part over the whole, to one decimal place")
        void isRoundedToOneDecimal() {
            assertThat(KpiRate.percent(1, 3)).isEqualByComparingTo("33.3");
            assertThat(KpiRate.percent(2, 3)).isEqualByComparingTo("66.7");
        }

        @Test
        @DisplayName("is zero when the part really is zero, which is a different fact from an empty denominator")
        void zeroOfSomethingIsZero() {
            assertThat(KpiRate.percent(0, 12)).isEqualByComparingTo("0.0");
        }

        @Test
        @DisplayName("is a hundred when everything counted")
        void everythingIsAHundred() {
            assertThat(KpiRate.percent(12, 12)).isEqualByComparingTo("100.0");
        }

        @Test
        @DisplayName("counts the complement from the late half, which is the half the database stores")
        void complementSubtracts() {
            // Ninety-two departures measured, seven of them late: 85 on time.
            assertThat(KpiRate.percentComplement(7, 92)).isEqualByComparingTo("92.4");
        }

        @Test
        @DisplayName("lets a rate per hundred pass a hundred, because one shipment can carry three problems")
        void per100CanExceedAHundred() {
            assertThat(KpiRate.per100(30, 10)).isEqualByComparingTo("300.0");
        }
    }

    @Nested
    @DisplayName("a utilisation percentage")
    class Utilisation {

        @Test
        @DisplayName("is the summed load over the summed capacity")
        void dividesTheTwoSums() {
            assertThat(KpiRate.percentOf(new BigDecimal("8200.500"), new BigDecimal("12000.000")))
                    .isEqualByComparingTo("68.3");
        }

        @Test
        @DisplayName("may exceed a hundred, because a shipment can be planned over its limit")
        void overloadedIsReportedAsSuch() {
            assertThat(KpiRate.percentOf(new BigDecimal("13000"), new BigDecimal("12000")))
                    .isEqualByComparingTo("108.3");
        }

        @Test
        @DisplayName("keeps the sign of a negative variance, which is what a saving looks like")
        void negativeStaysNegative() {
            assertThat(KpiRate.percentOf(new BigDecimal("-450.00"), new BigDecimal("9000.00")))
                    .isEqualByComparingTo("-5.0");
        }
    }
}
