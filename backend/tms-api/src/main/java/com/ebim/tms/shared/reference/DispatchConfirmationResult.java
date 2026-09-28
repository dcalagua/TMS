package com.ebim.tms.shared.reference;

import java.util.List;
import java.util.UUID;

/**
 * What TMS did with a dispatch document (ADR-013 sections 3 and 6).
 *
 * @param outcome APPLIED, RECONCILED, UNAPPLIED, RECORDED_UNMATCHED, UNCHANGED or STALE
 * @param firstReceipt whether this call stored the document (201) rather than answering a repeat (200)
 */
public record DispatchConfirmationResult(
        UUID id,
        String dispatchReference,
        int revision,
        String outcome,
        String transportReference,
        String verificationStatus,
        List<Discrepancy> discrepancies,
        boolean firstReceipt) {

    public DispatchConfirmationResult {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
    }

    /** One difference between the plan and what left. Business information, never an error. */
    public record Discrepancy(String code, String severity, String orderReference, Integer lineNumber,
            java.math.BigDecimal planned, java.math.BigDecimal dispatched, String uom, String detail) {
    }
}
