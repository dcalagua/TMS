package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.DiscrepancyType;
import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One thing wrong with an invoice, with the figures that make it wrong (migration V46).
 *
 * <p>{@code detail} is composed server-side from those figures, so the sentence a freight auditor
 * reads and the numbers behind it cannot drift.
 */
public record FreightDiscrepancyView(
        UUID id,
        DiscrepancyType type,
        BigDecimal expectedAmount,
        BigDecimal invoicedAmount,
        BigDecimal differenceAmount,
        String detail,
        FreightDiscrepancy.Status status,
        String resolutionNotes,
        OffsetDateTime resolvedAt) {

    public static FreightDiscrepancyView of(FreightDiscrepancy discrepancy) {
        return new FreightDiscrepancyView(discrepancy.id(), discrepancy.type(),
                discrepancy.expectedAmount(), discrepancy.invoicedAmount(), discrepancy.differenceAmount(),
                discrepancy.detail(), discrepancy.status(), discrepancy.resolutionNotes(),
                discrepancy.resolvedAt());
    }
}
