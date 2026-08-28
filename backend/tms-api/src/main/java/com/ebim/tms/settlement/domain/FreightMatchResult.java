package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * What comparing an invoice with its shipments concluded (migration V46).
 *
 * <p>Produced by {@link FreightMatcher}, a pure function, so the arithmetic behind a figure somebody
 * approved can be reproduced exactly from its inputs.
 *
 * @param status           the verdict
 * @param expectedAmount   what TMS priced the matched shipments at, or <b>null when no shipment on
 *                         this invoice carries an estimate</b>. Never zero for "unknown" - the rule
 *                         V45 set for delivered quantities and {@code ck_freight_match_unknown_is_
 *                         not_matched} enforces here
 * @param actualAmount     what was recorded as actually spent, or null when nobody recorded it
 * @param invoicedAmount   what the carrier billed. Always known - it is on the document
 * @param differenceAmount {@code invoiced - expected}, or null exactly when expected is null
 * @param discrepancies    what a freight auditor has to look at, empty when there is nothing
 */
public record FreightMatchResult(
        MatchStatus status,
        BigDecimal expectedAmount,
        BigDecimal actualAmount,
        BigDecimal invoicedAmount,
        BigDecimal differenceAmount,
        int matchedTripCount,
        int unmatchedLineCount,
        List<Discrepancy> discrepancies) {

    public FreightMatchResult {
        discrepancies = List.copyOf(discrepancies);
    }

    /** One thing wrong, with the figures that make it wrong beside it. */
    public record Discrepancy(
            DiscrepancyType type,
            BigDecimal expectedAmount,
            BigDecimal invoicedAmount,
            BigDecimal differenceAmount,
            String detail) {
    }
}
