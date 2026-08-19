package com.ebim.tms.planning.domain;

/**
 * How a planning run was built. Only {@link #MANUAL} is reachable in V1 - the value exists so a
 * future automatic run is a different {@code mode} on the same aggregate rather than a parallel
 * table, without implying that any solver exists (OR-Tools is deferred by decision).
 */
public enum PlanningMode {
    MANUAL,
    AUTOMATIC
}
