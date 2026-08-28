package com.ebim.tms.costing.application;

/**
 * What a profile is doing right now, for the chip on the configuration screen (V48, JOB 22).
 *
 * <p>Derived on read rather than stored: a profile becomes {@code EXPIRED} because a day passed,
 * and a stored state would need a job to keep it true - and JOB 07's refusal to invent a system
 * actor means there is no such job. Deriving it makes the screen correct with no background work
 * and nothing to fall out of date.
 */
public enum OwnFleetProfileState {

    /** In force today and able to produce a cost. */
    ACTIVE,

    /**
     * Saved but charging for nothing at all, so it can never produce a cost.
     *
     * <p>A draft is allowed to be saved this way - somebody configuring rates over two sittings
     * should not lose the first one - but the screen must say it costs nothing yet, and this is
     * what it says it with. The database refuses a profile with no component at all, so in practice
     * this appears for a profile whose components were later cleared.
     */
    INCOMPLETE,

    /** Its window has passed. Nothing falls back to it - a trip after it has no cost at all. */
    EXPIRED,

    /** Its window has not started. Configured ahead of a rate change, which is how they are entered. */
    FUTURE,

    /** Switched off by hand. Ignored by costing whatever its dates say. */
    INACTIVE
}
