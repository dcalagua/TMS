package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The answer to a reported payment (V67): {@code RECORDED} (201) or {@code DUPLICATE} (200), with the
 * invoice's status and balance after it.
 */
public record PayablePaymentResultV1(UUID invoiceId, String invoiceNumber, UUID paymentId, String outcome,
        String status, BigDecimal paidAmount, BigDecimal openBalance, LocalDate dueDate) {
}
