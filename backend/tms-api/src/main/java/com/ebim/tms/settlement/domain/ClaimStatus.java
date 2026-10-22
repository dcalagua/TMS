package com.ebim.tms.settlement.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a freight claim is (migration V71, {@code ck_freight_claim_status}).
 *
 * <pre>
 *   OPEN -> UNDER_REVIEW -> ACCEPTED | PARTIALLY_ACCEPTED | REJECTED -> CLOSED
 *   OPEN | UNDER_REVIEW -> CANCELLED
 * </pre>
 *
 * <p>The three decided states name the decision until the claim is closed; {@code resolution}
 * keeps it afterwards, which is why the decision is a column of its own and not only a status.
 */
public enum ClaimStatus {
    OPEN,
    UNDER_REVIEW,
    ACCEPTED,
    PARTIALLY_ACCEPTED,
    REJECTED,
    CLOSED,
    CANCELLED;

    private static final Set<ClaimStatus> DECIDED = EnumSet.of(ACCEPTED, PARTIALLY_ACCEPTED, REJECTED);
    private static final Set<ClaimStatus> FINISHED = EnumSet.of(CLOSED, CANCELLED);

    /** Waiting for its recovery to be documented, or a rejected one waiting to be filed. */
    public boolean isDecided() {
        return DECIDED.contains(this);
    }

    /** Nothing more happens to it. */
    public boolean isFinished() {
        return FINISHED.contains(this);
    }

    /** Somebody still has to do something: the incident centre's "open". */
    public boolean isActive() {
        return !isFinished();
    }
}
