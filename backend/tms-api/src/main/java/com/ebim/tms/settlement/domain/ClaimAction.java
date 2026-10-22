package com.ebim.tms.settlement.domain;

/**
 * What the claim's state lets somebody do next. The screen paints these and deduces nothing; the
 * permission behind each is still checked by the endpoint, and four eyes by {@link FreightClaim}.
 */
public enum ClaimAction {
    UPDATE,
    SUBMIT,
    ACCEPT,
    PARTIALLY_ACCEPT,
    REJECT,
    CLOSE,
    CANCEL,
    ATTACH_EVIDENCE
}
