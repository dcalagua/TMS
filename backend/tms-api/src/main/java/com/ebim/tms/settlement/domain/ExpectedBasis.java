package com.ebim.tms.settlement.domain;

/**
 * What an invoice, or one of its lines, was compared against (migration V64, ADR-024 part 1).
 *
 * <p>Said, because "expected 1,450" means two different things: the shipment's <b>actual</b> cost
 * once it was closed (V30), or its <b>estimate</b> while it is still open. An auditor judging a
 * difference needs to know which.
 */
public enum ExpectedBasis {
    /** The closed actual cost of the shipment. */
    ACTUAL,
    /** The estimate: the shipment's cost is not closed yet, or has no actual. */
    ESTIMATED,
    /** Some shipments of the invoice against their actual, others against their estimate. */
    MIXED;

    /** The basis of several shipments together, or null when there are none. */
    public static ExpectedBasis combine(java.util.Collection<ExpectedBasis> bases) {
        if (bases.isEmpty()) {
            return null;
        }
        boolean actual = bases.contains(ACTUAL) || bases.contains(MIXED);
        boolean estimated = bases.contains(ESTIMATED) || bases.contains(MIXED);
        return actual && estimated ? MIXED : (actual ? ACTUAL : ESTIMATED);
    }
}
