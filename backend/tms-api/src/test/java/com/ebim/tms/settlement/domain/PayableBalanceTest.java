package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What is still owed on an invoice (V67, ADR-023 section 2; withholdings as a list since ADR-027). */
class PayableBalanceTest {

    @ParameterizedTest(name = "net {0}, withheld {1}, paid {2} -> payable {3}, open {4}")
    @CsvSource({
            // Peru: a detraction of 40.
            "1000.00,  40.00,   0.00,  960.00,  960.00",
            "1000.00,  40.00, 400.00,  960.00,  560.00",
            // Peru: a retention of 9.
            " 300.00,   9.00, 100.00,  291.00,  191.00",
            // Ecuador: IR 1% (10.00) and IVA 70% (105.00) on one document.
            "1150.00, 115.00,   0.00, 1035.00, 1035.00",
            " 300.00,       , 300.00,  300.00,    0.00",
            // A credit note after the payments: overpaid, shown rather than clamped.
            " 250.00,       , 300.00,  250.00,  -50.00"})
    @DisplayName("payable = net - sum of withholdings; open = payable - paid")
    void formula(BigDecimal net, BigDecimal withheld, BigDecimal paid, BigDecimal payable, BigDecimal open) {
        PayableBalance balance = PayableBalance.of(net, withheld, paid);
        assertThat(balance.payableAmount()).isEqualByComparingTo(payable);
        assertThat(balance.openBalance()).isEqualByComparingTo(open);
    }

    @Test
    @DisplayName("a payment fits up to the cent of the open balance, and not a cent more")
    void admits() {
        PayableBalance balance = PayableBalance.of(new BigDecimal("300.00"), null, new BigDecimal("100.00"));
        assertThat(balance.admits(new BigDecimal("200.00"))).isTrue();
        assertThat(balance.admits(new BigDecimal("200.01"))).isFalse();
        assertThat(balance.admits(new BigDecimal("0.00"))).isFalse();
        assertThat(balance.admits(null)).isFalse();
        assertThat(balance.openBalanceAfter(new BigDecimal("200.00"))).isEqualByComparingTo("0.00");
    }
}
