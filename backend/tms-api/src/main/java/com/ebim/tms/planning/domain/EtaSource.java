package com.ebim.tms.planning.domain;

/**
 * What an arrival estimate is actually built on (migration V43).
 *
 * <p>Two values and not three: {@code CACHE} is deliberately absent. V38 records the defect that
 * makes it absent - serving a cached row once overwrote {@code FALLBACK} with {@code CACHE}, and a
 * straight-line guess became indistinguishable from a measured road the moment it was stored.
 * Whether an estimate came from a cache is a fact about the lookup; what it was measured over is a
 * fact about the number, and only the second belongs here.
 */
public enum EtaSource {

    /** Every leg up to and including this stop was measured over a real road network. */
    MEASURED_ROUTE,

    /**
     * At least one leg on the way was a straight line.
     *
     * <p>Provenance degrades along a chain and never upgrades. One fallback leg makes every stop
     * after it {@code FALLBACK}, because that is what those arrival times are genuinely built on -
     * a later measured leg does not repair the estimate it was added to.
     */
    FALLBACK;

    /** The weaker of two, which is what a chain of legs carries forward. */
    public EtaSource degradedWith(EtaSource other) {
        return this == FALLBACK || other == FALLBACK ? FALLBACK : MEASURED_ROUTE;
    }
}
