package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The facts of one payment as reported (V67, ADR-027): what a {@link CarrierPayment} records and what
 * a {@link CarrierPaymentRequest} carries until a second person confirms it.
 *
 * @param amount       paid to the carrier, in the invoice's currency
 * @param withholdings what was withheld and deposited elsewhere, with its certificate; informative here
 */
public record PaymentDetail(String paymentReference, LocalDate paidOn, BigDecimal amount, String currency,
        List<ReportedWithholding> withholdings) {

    public PaymentDetail {
        withholdings = withholdings == null ? List.of() : List.copyOf(withholdings);
    }

    /** Whether a repeat of the same reference reports the same payment (a DUPLICATE) or another one (409). */
    public boolean sameAs(PaymentDetail other) {
        return paymentReference.equals(other.paymentReference)
                && paidOn.equals(other.paidOn)
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency);
    }
}
