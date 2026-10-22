package com.ebim.tms.shared.reference;

/**
 * What an <em>expired</em> fleet document does to a planning or a work day (migration V72).
 *
 * <p>Only expiry is ever enforced. A document that is missing, or on file without an expiry date,
 * warns at most - "we do not know" is not "it has expired", the rule V26 wrote for the licence.
 */
public enum ComplianceEnforcement {

    /** An expired document refuses the assignment and stops the dispatch. */
    BLOCK,

    /** An expired document is reported, and nothing is refused. */
    WARN
}
