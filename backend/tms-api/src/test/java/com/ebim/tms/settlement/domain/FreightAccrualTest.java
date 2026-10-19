package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accrual's arithmetic and states (V65, ADR-023). Every method that moves a total returns the
 * movement to book; these tests keep a ledger of what was returned and check that it always sums to
 * the balance - the property the database test proves on real rows.
 */
class FreightAccrualTest {

    private final List<BigDecimal> movements = new ArrayList<>();

    @Test
    @DisplayName("accrue -> adjust -> consume partially -> consume totally")
    void fullCycle() {
        FreightAccrual accrual = accrual("1450.00");
        assertThat(accrual.status()).isEqualTo(AccrualStatus.OPEN);
        assertThat(accrual.basis()).isEqualTo(AccrualBasis.ESTIMATED);

        book(accrual.adjustTo(new BigDecimal("1500")));
        assertThat(accrual.basis()).isEqualTo(AccrualBasis.ACTUAL);
        assertThat(accrual.accruedAmount()).isEqualByComparingTo("1500.00");

        book(accrual.consume(new BigDecimal("1000.00")));
        assertThat(accrual.status()).isEqualTo(AccrualStatus.PARTIALLY_SETTLED);
        assertThat(accrual.balance()).isEqualByComparingTo("500.00");

        book(accrual.consume(new BigDecimal("500.00")));
        assertThat(accrual.status()).isEqualTo(AccrualStatus.SETTLED);
        assertThat(accrual.balance()).isEqualByComparingTo("0.00");
        assertLedger(accrual);
    }

    @Test
    @DisplayName("an adjustment that changes nothing books nothing but records the actual basis")
    void adjustToSame() {
        FreightAccrual accrual = accrual("800.00");
        assertThat(accrual.adjustTo(new BigDecimal("800.00")).signum()).isZero();
        assertThat(accrual.basis()).isEqualTo(AccrualBasis.ACTUAL);
    }

    @Test
    @DisplayName("an adjustment downwards is negative and is only made while open")
    void adjustDownOnlyWhileOpen() {
        FreightAccrual accrual = accrual("800.00");
        book(accrual.adjustTo(new BigDecimal("650.00")));
        assertThat(movements.get(1)).isEqualByComparingTo("-150.00");
        book(accrual.consume(new BigDecimal("100.00")));
        assertThatThrownBy(() -> accrual.adjustTo(new BigDecimal("700.00")))
                .isInstanceOf(IllegalStateException.class);
        assertLedger(accrual);
    }

    @Test
    @DisplayName("invoiced above the accrual: settled, with a negative balance that is the variance")
    void overInvoiced() {
        FreightAccrual accrual = accrual("1000.00");
        book(accrual.consume(new BigDecimal("1100.00")));
        assertThat(accrual.status()).isEqualTo(AccrualStatus.SETTLED);
        assertThat(accrual.balance()).isEqualByComparingTo("-100.00");
        assertLedger(accrual);
    }

    @Test
    @DisplayName("closing the remainder settles a partial accrual; reversing an open one reverses it")
    void reverse() {
        FreightAccrual partial = accrual("1450.00");
        book(partial.consume(new BigDecimal("1400.00")));
        book(partial.reverseRemainder());
        assertThat(partial.status()).isEqualTo(AccrualStatus.SETTLED);
        assertThat(partial.reversedAmount()).isEqualByComparingTo("50.00");
        assertLedger(partial);

        movements.clear();
        FreightAccrual open = accrual("300.00");
        book(open.reverseRemainder());
        assertThat(open.status()).isEqualTo(AccrualStatus.REVERSED);
        assertThat(open.balance()).isEqualByComparingTo("0.00");
        assertLedger(open);
        assertThatThrownBy(open::reverseRemainder).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.consume(BigDecimal.TEN)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an accrual is never born at zero, and an invoice never consumes zero")
    void noZeroes() {
        assertThatThrownBy(() -> accrual("0.00")).isInstanceOf(IllegalArgumentException.class);
        FreightAccrual accrual = accrual("10.00");
        assertThatThrownBy(() -> accrual.consume(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each movement type carries the sign the database enforces")
    void signs() {
        assertThat(AccrualEntryType.ACCRUE.accepts(BigDecimal.ONE)).isTrue();
        assertThat(AccrualEntryType.ACCRUE.accepts(BigDecimal.ONE.negate())).isFalse();
        assertThat(AccrualEntryType.CONSUME.accepts(BigDecimal.ONE.negate())).isTrue();
        assertThat(AccrualEntryType.CONSUME.accepts(BigDecimal.ONE)).isFalse();
        assertThat(AccrualEntryType.REVERSE.accepts(BigDecimal.ONE.negate())).isTrue();
        assertThat(AccrualEntryType.ADJUST.accepts(BigDecimal.ONE.negate())).isTrue();
        assertThat(AccrualEntryType.ADJUST.accepts(BigDecimal.ZERO)).isFalse();
    }

    @Test
    @DisplayName("a reversal or a manual movement without a reason is refused")
    void reasonRequired() {
        FreightAccrual accrual = accrual("10.00");
        assertThatThrownBy(() -> new FreightAccrualEntry(accrual, AccrualEntryType.REVERSE, new BigDecimal("-10.00"),
                LocalDate.of(2026, 10, 18), AccrualSourceType.MANUAL, "k", null, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        FreightAccrualEntry entry = new FreightAccrualEntry(accrual, AccrualEntryType.ACCRUE, BigDecimal.TEN,
                LocalDate.of(2026, 10, 18), AccrualSourceType.TRIP_DISPATCHED, "k", null, null, null);
        assertThat(entry.period()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    private FreightAccrual accrual(String amount) {
        FreightAccrual accrual = new FreightAccrual(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PEN",
                AccrualBasis.ESTIMATED, LocalDate.of(2026, 10, 3), new BigDecimal(amount));
        movements.add(accrual.accruedAmount());
        return accrual;
    }

    private void book(BigDecimal movement) {
        if (movement.signum() != 0) {
            movements.add(movement);
        }
    }

    private void assertLedger(FreightAccrual accrual) {
        assertThat(movements.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(accrual.balance());
    }
}
