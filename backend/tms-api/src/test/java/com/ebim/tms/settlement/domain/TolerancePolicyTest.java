package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The policy as written by a person, and the tolerance it yields per invoice currency (V64). */
class TolerancePolicyTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    @DisplayName("absolute in the invoice's currency: both bounds apply")
    void sameCurrencyKeepsBothBounds() {
        TolerancePolicy policy = new TolerancePolicy(COMPANY, null, new BigDecimal("50"), new BigDecimal("2"),
                "PEN", ACTOR);

        Tolerance tolerance = policy.toleranceFor("PEN");
        assertThat(tolerance.covers(new BigDecimal("1000"), new BigDecimal("1050"))).isTrue();
        assertThat(tolerance.covers(new BigDecimal("10000"), new BigDecimal("10200"))).isTrue();
    }

    @Test
    @DisplayName("absolute in another currency: only the percentage applies")
    void otherCurrencyDropsTheAbsoluteBound() {
        TolerancePolicy policy = new TolerancePolicy(COMPANY, null, new BigDecimal("50"), new BigDecimal("2"),
                "PEN", ACTOR);

        Tolerance tolerance = policy.toleranceFor("USD");
        assertThat(tolerance.absoluteAmount()).isNull();
        assertThat(tolerance.covers(new BigDecimal("1000"), new BigDecimal("1050"))).isFalse();
        assertThat(tolerance.covers(new BigDecimal("1000"), new BigDecimal("1020"))).isTrue();
    }

    @Test
    @DisplayName("absolute only, other currency: no tolerance at all")
    void absoluteOnlyInAnotherCurrencyIsNone() {
        TolerancePolicy policy = new TolerancePolicy(COMPANY, UUID.randomUUID(), new BigDecimal("50"), null,
                "PEN", ACTOR);

        assertThat(policy.toleranceFor("USD").isConfigured()).isFalse();
    }

    @Test
    @DisplayName("percentage only applies whatever the currency")
    void percentageIsCurrencyFree() {
        TolerancePolicy policy = new TolerancePolicy(COMPANY, null, null, new BigDecimal("3"), null, ACTOR);

        assertThat(policy.toleranceFor("USD").covers(new BigDecimal("1000"), new BigDecimal("1030"))).isTrue();
    }

    @Test
    @DisplayName("a policy states a bound, and an absolute bound its currency")
    void boundsAreRequired() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TolerancePolicy(COMPANY, null, null, null, null, ACTOR));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TolerancePolicy(COMPANY, null, BigDecimal.TEN, null, null, ACTOR));
    }
}
