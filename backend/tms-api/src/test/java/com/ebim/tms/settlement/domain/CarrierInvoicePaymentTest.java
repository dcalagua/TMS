package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The invoice after the export (V67, ADR-023 section 2): due date, schedule, hold and payments. */
class CarrierInvoicePaymentTest {

    private static final UUID PERSON = UUID.randomUUID();

    @Test
    @DisplayName("the due date is the invoice date plus the terms, and the terms are bounded")
    void dueDate() {
        CarrierInvoice invoice = exported();
        invoice.fixDueDate(45);
        assertThat(invoice.dueDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(invoice.paymentTermsDays()).isEqualTo(45);
        assertThatThrownBy(() -> invoice.fixDueDate(366)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("partial payments, then paid; nothing after paid")
    void partialThenPaid() {
        CarrierInvoice invoice = exported();
        invoice.applyPayment(new BigDecimal("100.00"), new BigDecimal("200.00"), PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        invoice.applyPayment(new BigDecimal("150.00"), new BigDecimal("50.00"), PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        invoice.applyPayment(new BigDecimal("50.00"), BigDecimal.ZERO, PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.paidAmount()).isEqualByComparingTo("300.00");
        assertThatThrownBy(() -> invoice.applyPayment(BigDecimal.ONE, BigDecimal.ZERO, PERSON))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an overpayment never reaches the aggregate")
    void overpaymentRefused() {
        CarrierInvoice invoice = exported();
        assertThatThrownBy(() -> invoice.applyPayment(new BigDecimal("301.00"), new BigDecimal("-1.00"), PERSON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.EXPORTED);
    }

    @Test
    @DisplayName("hold needs a reason, blocks a schedule, and releases to scheduled or partially paid")
    void hold() {
        CarrierInvoice invoice = exported();
        assertThatThrownBy(() -> invoice.placeOnHold(" ", PERSON)).isInstanceOf(InvalidRequestException.class);
        invoice.placeOnHold("Claim", PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.ON_HOLD);
        assertThatThrownBy(() -> invoice.schedule(LocalDate.of(2026, 11, 1), PERSON))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> invoice.releaseHold("", PERSON)).isInstanceOf(InvalidRequestException.class);
        invoice.releaseHold("Claim withdrawn", PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.SCHEDULED);
        assertThat(invoice.onHoldReason()).isNull();

        invoice.applyPayment(new BigDecimal("100.00"), new BigDecimal("200.00"), PERSON);
        invoice.placeOnHold("Second claim", PERSON);
        invoice.releaseHold("Resolved", PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("a payment recorded on a held invoice ends the hold")
    void paymentEndsHold() {
        CarrierInvoice invoice = exported();
        invoice.placeOnHold("Claim", PERSON);
        invoice.applyPayment(new BigDecimal("300.00"), BigDecimal.ZERO, PERSON);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.onHoldReason()).isNull();
    }

    @Test
    @DisplayName("nothing is scheduled or held before the export")
    void beforeTheExport() {
        CarrierInvoice invoice = invoice();
        invoice.transitionTo(InvoiceStatus.MATCHING, PERSON);
        invoice.transitionTo(InvoiceStatus.MATCHED, PERSON);
        invoice.transitionTo(InvoiceStatus.APPROVED, PERSON);
        assertThatThrownBy(() -> invoice.schedule(LocalDate.of(2026, 11, 1), PERSON))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> invoice.placeOnHold("x", PERSON)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> invoice.applyPayment(BigDecimal.ONE, BigDecimal.ZERO, PERSON))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("four eyes: the person who keyed a payment cannot confirm it, and a decision is final")
    void fourEyes() {
        CarrierInvoice invoice = exported();
        CarrierPaymentRequest request = new CarrierPaymentRequest(invoice, new PaymentDetail("TRX", LocalDate.of(2026,
                11, 1), new BigDecimal("10.00"), "PEN", null), PERSON);
        assertThatThrownBy(() -> request.confirm(PERSON, UUID.randomUUID())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> CarrierPayment.confirmed(request, invoice, PERSON))
                .isInstanceOf(IllegalArgumentException.class);
        UUID checker = UUID.randomUUID();
        request.confirm(checker, UUID.randomUUID());
        assertThat(request.status()).isEqualTo(PaymentRequestStatus.CONFIRMED);
        assertThatThrownBy(() -> request.reject(checker, "late")).isInstanceOf(ConflictException.class);
    }

    private static CarrierInvoice invoice() {
        return new CarrierInvoice(UUID.randomUUID(), UUID.randomUUID(), "F-1", LocalDate.of(2026, 10, 5), "PEN",
                new BigDecimal("300.00"), null, null, PERSON);
    }

    private static CarrierInvoice exported() {
        CarrierInvoice invoice = invoice();
        invoice.transitionTo(InvoiceStatus.MATCHING, PERSON);
        invoice.transitionTo(InvoiceStatus.MATCHED, PERSON);
        invoice.transitionTo(InvoiceStatus.APPROVED, PERSON);
        invoice.transitionTo(InvoiceStatus.EXPORTED, PERSON);
        return invoice;
    }
}
