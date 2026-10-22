package com.ebim.tms.settlement.domain;

/** The decision on a freight claim (migration V71, {@code ck_freight_claim_resolution}); kept after it is closed. */
public enum ClaimResolution {
    ACCEPTED,
    PARTIALLY_ACCEPTED,
    REJECTED;

    /** The claim's status while it is decided and not yet closed. */
    public ClaimStatus status() {
        return ClaimStatus.valueOf(name());
    }

    /** Whether the carrier owes something. */
    public boolean recovers() {
        return this != REJECTED;
    }
}
