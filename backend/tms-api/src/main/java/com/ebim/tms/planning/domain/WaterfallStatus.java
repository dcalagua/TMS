package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * How a tender waterfall ended, or that it has not (migration V40).
 *
 * <p>Only {@link #ACTIVE} is running; the other three are the three ways it stops, and they are
 * kept apart because a dispatcher acts differently on each. An exhausted waterfall needs a carrier
 * nobody has a rate for; a cancelled one needs nothing at all.
 */
public enum WaterfallStatus {

    /** Running: a candidate is out, or the next one is about to be offered. */
    ACTIVE,

    /** A carrier said yes. The shipment has its carrier and the waterfall is over. */
    ACCEPTED,

    /**
     * Every candidate refused, lapsed, or the attempt ceiling was reached.
     *
     * <p>Its own state rather than a cancelled one: nobody decided to stop, the list ran out, and
     * the shipment now needs a human to find a carrier the ranking did not contain.
     */
    EXHAUSTED,

    /** A person stopped it - the manual override. */
    CANCELLED;

    private static final Set<WaterfallStatus> FINISHED = EnumSet.of(ACCEPTED, EXHAUSTED, CANCELLED);

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isFinished() {
        return FINISHED.contains(this);
    }
}
