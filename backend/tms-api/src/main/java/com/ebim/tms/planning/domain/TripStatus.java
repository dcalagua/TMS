package com.ebim.tms.planning.domain;

/**
 * The minimal V1 trip lifecycle: {@link #DRAFT} while its run is a draft (the only state in which
 * vehicle, assignments and stops may change), {@link #CONFIRMED} when its run is confirmed - which
 * also freezes the capacity the trip was validated against - and {@link #CANCELLED} for a draft
 * trip the planner discards, which releases its orders back to the eligible pool.
 *
 * <p>A confirmed trip is locked: {@code TripService} refuses every mutation on it. See
 * {@code docs/domain/PLANNING_MANUAL_V1.md}, "State rules".
 */
public enum TripStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}
