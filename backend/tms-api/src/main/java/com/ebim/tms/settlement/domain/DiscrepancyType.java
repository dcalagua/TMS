package com.ebim.tms.settlement.domain;

/**
 * Why an invoice does not agree with what TMS expected (migration V46).
 *
 * <p>Six values, and no more until somebody needs to count a seventh. A catalogue of fifty codes
 * that nobody populates is worse than six that are always accurate - the same argument
 * {@code UnplannedReason} and {@code CostComponentReason} make.
 *
 * <p>Each one implies a different next action, which is the test for whether a value earns its
 * place: a duplicate is refused outright, an unmatched trip is a data problem, and a total
 * difference is a conversation with the carrier.
 */
public enum DiscrepancyType {

    /** The invoice total differs from the expected total by more than tolerance. */
    TOTAL_AMOUNT,

    /** One line differs from what its shipment was priced at. */
    LINE_AMOUNT,

    /**
     * A line names no shipment, or names one this invoice's carrier did not run.
     *
     * <p>A data problem before it is a money problem: nothing can be compared until it is resolved.
     */
    UNMATCHED_TRIP,

    /**
     * This carrier has already billed this number.
     *
     * <p>The most common freight-audit fraud and the most common honest mistake.
     * {@code uq_carrier_invoice_number} refuses the same <em>number</em> outright at insert, so
     * this type is for the case the database cannot see: the same <b>shipment</b> billed twice -
     * under two different numbers, or twice on one document.
     *
     * <p>{@link FreightMatcher} raises it both ways, and the duplicate line is kept out of the
     * expected total so the invoice comes up over rather than matching itself. It is a discrepancy
     * and not a refusal because re-billing after a credit note is legal; what it does is make an
     * approval impossible until somebody has said which it is.
     */
    DUPLICATE_INVOICE,

    /** The invoice is in one currency and the shipment was priced in another. Never converted. */
    CURRENCY_MISMATCH,

    /**
     * A matched shipment has no estimated cost, so there is nothing to compare it against.
     *
     * <p>Raised as a discrepancy rather than silently skipped, because "we cannot check this line"
     * is something a freight auditor must be told before they approve it.
     */
    MISSING_EXPECTED_COST
}
