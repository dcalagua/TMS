package com.ebim.tms.orders.domain;

/**
 * Why an order is not simply {@link Eligibility#ELIGIBLE} for release (ADR-014 section 4). Each code
 * carries its severity and whether a person must give a reason to release over it - stated once,
 * here, so the release gate, the board and the integration upsert cannot disagree.
 */
public enum SchedulingReasonCode {

    /** The origin is not an active location holding the ORIGIN role. */
    MISSING_ORIGIN(Eligibility.BLOCKED, false),
    /** The destination is not an active location holding the DESTINATION role. */
    MISSING_DESTINATION(Eligibility.BLOCKED, false),
    /** Weight, volume and pallets are all zero - today's mark-ready rule, unchanged. */
    MISSING_CAPACITY(Eligibility.BLOCKED, false),
    /** The origin has active routes and none of them stops at the destination. */
    ROUTE_NOT_FOUND(Eligibility.BLOCKED, false),
    /** Several active routes stop at the destination, and none is picked silently. */
    ROUTE_AMBIGUOUS(Eligibility.BLOCKED, false),
    /** At least one active blocking hold (V54). */
    ACTIVE_BLOCKING_HOLD(Eligibility.BLOCKED, false),

    /** Now is past the release deadline for the scheduled dispatch date. */
    CUTOFF_MISSED(Eligibility.WARNING, true),
    /** The destination's and/or the route's calendar does not normally serve the dispatch date. */
    FREQUENCY_OVERRIDE(Eligibility.WARNING, true),
    /**
     * The company has no active route at all from this origin. Informative: shown, never blocking,
     * and it needs no override reason - which is what keeps a company that never modelled routes
     * releasing exactly as before.
     */
    ROUTE_NOT_CONFIGURED(Eligibility.WARNING, false);

    private final Eligibility severity;
    private final boolean requiresOverride;

    SchedulingReasonCode(Eligibility severity, boolean requiresOverride) {
        this.severity = severity;
        this.requiresOverride = requiresOverride;
    }

    /** {@link Eligibility#BLOCKED} or {@link Eligibility#WARNING}; never {@code ELIGIBLE}. */
    public Eligibility severity() {
        return severity;
    }

    public boolean requiresOverride() {
        return requiresOverride;
    }
}
