package com.ebim.tms.planning.domain;

/**
 * How a trip departed (ADR-013 section 2, migration V52). Names no product: a third-party warehouse
 * system is {@link #INTEGRATION} exactly as EWM by EBIM is.
 */
public enum DispatchSource {
    /** A person dispatched it, the way every trip departed before V52. */
    OPERATOR,
    /** A warehouse system's dispatch document dispatched it; the credential is the actor. */
    INTEGRATION,
    /** A person dispatched it past a company that requires the warehouse to confirm, with a reason. */
    OPERATOR_OVERRIDE
}
