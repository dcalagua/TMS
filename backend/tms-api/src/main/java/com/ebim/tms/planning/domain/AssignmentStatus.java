package com.ebim.tms.planning.domain;

/**
 * Whether an order-to-trip assignment is the one currently in force ({@link #ACTIVE}) or a closed
 * historical record ({@link #REMOVED}).
 *
 * <p>Removal never deletes the row: a reassignment closes the prior assignment and opens a new
 * one, so "this order was on trip 3 this morning and was moved to trip 5 at 11:20 by X" stays
 * answerable. Capacity and every planning screen read {@link #ACTIVE} rows only - see
 * {@code docs/domain/PLANNING_MANUAL_V1.md}, "Why an assignment aggregate".
 */
public enum AssignmentStatus {
    ACTIVE,
    REMOVED
}
