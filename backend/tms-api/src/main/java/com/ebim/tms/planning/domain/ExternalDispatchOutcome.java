package com.ebim.tms.planning.domain;

/** What TMS did with a stored dispatch document (ADR-013 section 3). */
public enum ExternalDispatchOutcome {
    /** This document dispatched the trip. */
    APPLIED,
    /** Compared only: the trip had already departed, or the company is in MANUAL. */
    RECONCILED,
    /** It would dispatch, but the trip's state or a database invariant needs a person. */
    UNAPPLIED,
    /** Its transportReference names no trip of the company. */
    RECORDED_UNMATCHED
}
