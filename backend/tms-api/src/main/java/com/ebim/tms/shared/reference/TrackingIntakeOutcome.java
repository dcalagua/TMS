package com.ebim.tms.shared.reference;

/**
 * What intake did with one reported position.
 *
 * <p>Seven values in two groups, and the split is the contract: {@link #accepted()} says whether
 * the sender has anything to do about it. The first four are all "we have your data and you are
 * done" - three of them simply did not need a row - and only the last three are refusals the sender
 * can act on. A feed that retries on anything other than a refusal is a feed that never converges.
 *
 * <p>Why the three no-row outcomes are reported rather than folded into one "accepted": they are
 * how a partner tunes their own sender without asking us. A feed that sees 95% {@link #THINNED} is
 * pushing ten times faster than the deployment stores, which costs them bandwidth and costs us
 * nothing; a feed that sees {@link #STALE} is delivering out of order, which is a bug on their
 * side that no amount of retrying fixes. One opaque "accepted" would hide both.
 */
public enum TrackingIntakeOutcome {

    /** Stored. */
    RECORDED,

    /**
     * This feed already reported this shipment at this instant. The unique index is what makes a
     * replayed hour of pings a no-op, so an at-least-once sender needs no cursor of its own.
     */
    DUPLICATE,

    /**
     * Dropped by the sampling policy: closer to the previously kept point than the configured
     * interval. Not an error and not a rate limit - the request was fine, the data was denser than
     * this deployment keeps. See {@code docs/domain/TRACKING_V1.md}, "Volume and retention".
     */
    THINNED,

    /**
     * Older than the newest position already stored for this shipment and feed. Dropped, because
     * a position feed answers "where is it now": a late-arriving older ping cannot improve that
     * answer, and storing it would defeat the spacing {@link #THINNED} enforces.
     */
    STALE,

    /** No shipment of that number in this company - or none this credential may report against. */
    UNKNOWN_SHIPMENT,

    /**
     * The shipment exists but is not out on the road: it has not been dispatched, or it was
     * cancelled. A position against it is a sender pointing at the wrong shipment, and accepting
     * it would put a vehicle on the map for a trip that never left.
     */
    NOT_TRACKABLE,

    /** The report itself is not usable - a coordinate out of range, a time in the future. */
    INVALID;

    /** Whether TMS has what the sender sent and the sender need do nothing further. */
    public boolean accepted() {
        return this == RECORDED || this == DUPLICATE || this == THINNED || this == STALE;
    }
}
