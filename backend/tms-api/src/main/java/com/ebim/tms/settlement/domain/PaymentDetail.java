package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The facts of one payment as reported (V67): what a {@link CarrierPayment} records and what a
 * {@link CarrierPaymentRequest} carries until a second person confirms it.
 *
 * @param amount paid to the carrier, in the invoice's currency
 * @param detractionAmount deposited in the carrier's detraction account; informative here
 * @param retentionAmount withheld and paid to SUNAT; informative here
 */
public record PaymentDetail(String paymentReference, LocalDate paidOn, BigDecimal amount, String currency,
        String detractionConstancyNumber, LocalDate detractionConstancyDate, BigDecimal detractionAmount,
        BigDecimal retentionAmount) {

    /** Whether a repeat of the same reference reports the same payment (a DUPLICATE) or another one (409). */
    public boolean sameAs(PaymentDetail other) {
        return paymentReference.equals(other.paymentReference)
                && paidOn.equals(other.paidOn)
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency);
    }
}
