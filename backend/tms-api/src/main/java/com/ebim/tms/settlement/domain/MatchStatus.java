package com.ebim.tms.settlement.domain;

/**
 * What the three-way comparison concluded (migration V46).
 *
 * <p>Three values, and the third is the reason this enum exists rather than a boolean.
 */
public enum MatchStatus {

    /** The invoice agrees with what TMS expected, within the tolerance that applied. */
    MATCHED,

    /** It disagrees by more than the tolerance allowed. */
    DISCREPANCY,

    /**
     * There is nothing to compare against.
     *
     * <p>No shipment on this invoice carries an estimate, so TMS has <b>no opinion</b> about
     * whether the amount is right. Emphatically not {@code DISCREPANCY}: nothing is wrong, and
     * telling an auditor that a correct invoice is disputed wastes exactly the attention this
     * module exists to direct.
     *
     * <p>Equally not {@code MATCHED}, which {@code ck_freight_match_unknown_is_not_matched}
     * enforces in the database. Treating an unknown expected figure as 0.00 would report the whole
     * invoice as an overcharge - the same "absent is not zero" rule V45 established for delivered
     * quantities.
     */
    UNMATCHABLE
}
