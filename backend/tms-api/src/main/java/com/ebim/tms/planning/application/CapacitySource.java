package com.ebim.tms.planning.application;

/**
 * Where a trip's limits came from: {@link #LIVE} re-reads the vehicle's current effective
 * capacity (a draft trip), {@link #SNAPSHOT} reads the values frozen when the trip was confirmed.
 * Exposed in the API so a screen can tell a planner which of the two they are looking at - see
 * {@code docs/domain/CAPACITY_MODEL.md}.
 */
public enum CapacitySource {
    LIVE,
    SNAPSHOT,
    /** No vehicle is assigned yet, so there is nothing to read from - every dimension is unlimited. */
    NONE
}
