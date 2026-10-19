package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.FreightAccrualEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One movement of an accrual (V65). {@code amount} is signed: what it did to the balance. */
public record AccrualEntryView(
        UUID id,
        String entryType,
        BigDecimal amount,
        LocalDate period,
        String sourceType,
        String sourceKey,
        UUID carrierInvoiceId,
        String reason,
        UUID createdBy,
        OffsetDateTime createdAt) {

    public static AccrualEntryView of(FreightAccrualEntry entry) {
        return new AccrualEntryView(entry.id(), entry.entryType().name(), entry.amount(), entry.period(),
                entry.sourceType().name(), entry.sourceKey(), entry.carrierInvoiceId(), entry.reason(),
                entry.createdBy(), entry.createdAt());
    }
}
