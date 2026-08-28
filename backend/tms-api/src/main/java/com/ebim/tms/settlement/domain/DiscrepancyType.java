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
     * {@code uq_carrier_invoice_number} refuses it outright at insert, so this type exists for the
     * case the database cannot see: the same shipment billed twice under two different numbers.
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
