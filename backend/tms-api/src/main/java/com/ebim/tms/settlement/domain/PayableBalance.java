package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What is still owed to the carrier on one invoice (V67, ADR-023 section 2). Pure.
 *
 * <pre>
 *   net      = total - approved credit notes + approved debit notes
 *   payable  = net - detraction - retention      (those two are deposited elsewhere, not to the carrier)
 *   open     = payable - paid
 * </pre>
 *
 * <p>A detraction or retention the invoice does not carry counts as zero. The open balance may be
 * negative only if a credit note arrived after the payments: the record says so rather than
 * clamping, because a carrier who was overpaid is a fact somebody must see.
 */
public record PayableBalance(BigDecimal netAmount, BigDecimal detractionAmount, BigDecimal retentionAmount,
        BigDecimal payableAmount, BigDecimal paidAmount, BigDecimal openBalance) {

    public static PayableBalance of(BigDecimal netAmount, BigDecimal detractionAmount, BigDecimal retentionAmount,
            BigDecimal paidAmount) {
        BigDecimal net = money(netAmount);
        BigDecimal detraction = detractionAmount == null ? null : money(detractionAmount);
        BigDecimal retention = retentionAmount == null ? null : money(retentionAmount);
        BigDecimal payable = net.subtract(zeroIfNull(detraction)).subtract(zeroIfNull(retention));
        BigDecimal paid = money(paidAmount == null ? BigDecimal.ZERO : paidAmount);
        return new PayableBalance(net, detraction, retention, payable, paid, payable.subtract(paid));
    }

    /** Whether this payment fits in what is still owed. */
    public boolean admits(BigDecimal amount) {
        return amount != null && amount.signum() > 0 && money(amount).compareTo(openBalance) <= 0;
    }

    /** The open balance once this payment is applied. */
    public BigDecimal openBalanceAfter(BigDecimal amount) {
        return openBalance.subtract(money(amount));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
