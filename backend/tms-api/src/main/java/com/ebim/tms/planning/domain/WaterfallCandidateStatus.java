package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * What happened to one carrier on the list (migration V40).
 *
 * <p>Deliberately not the same vocabulary as {@link TenderStatus}. A tender is an offer and has its
 * own lifecycle; this is a position in a queue, and the two diverge exactly where it matters:
 * {@link #SKIPPED} has no tender at all, because the waterfall never got that far.
 */
public enum WaterfallCandidateStatus {

    /** Not offered yet. */
    PENDING,

    /** The offer is out and nothing has come back. */
    OFFERED,

    /** This carrier accepted. At most one per waterfall, mirroring {@code uq_trip_tender_accepted}. */
    ACCEPTED,

    /** This carrier said no. */
    REJECTED,

    /** The deadline passed with no answer. Nobody did this; a clock did. */
    EXPIRED,

    /**
     * Never offered, because the waterfall ended first - accepted, cancelled, or out of attempts.
     *
     * <p>The one status with no tender behind it, which is why
     * {@code ck_twc_decided_has_tender} exempts it: there is nothing to point at, and inventing an
     * empty tender so the column could be non-null would put a record of an offer that was never
     * made into the history a carrier may one day read.
     */
    SKIPPED;

    private static final Set<WaterfallCandidateStatus> DECIDED =
            EnumSet.of(ACCEPTED, REJECTED, EXPIRED, SKIPPED);

    /** Whether this candidate is still waiting to be offered. */
    public boolean isPending() {
        return this == PENDING;
    }

    /** Whether an offer is out right now. */
    public boolean isOffered() {
        return this == OFFERED;
    }

    /** Whether this candidate is settled, one way or another. */
    public boolean isDecided() {
        return DECIDED.contains(this);
    }

    /** Whether it counts against the attempt ceiling: an offer was actually made. */
    public boolean consumedAnAttempt() {
        return this == OFFERED || this == ACCEPTED || this == REJECTED || this == EXPIRED;
    }
}
