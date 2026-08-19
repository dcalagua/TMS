package com.ebim.tms.planning.domain;

/**
 * The minimal V1 planning-run lifecycle, documented in full in
 * {@code docs/domain/PLANNING_MANUAL_V1.md}: a run is created {@link #DRAFT} and is the only
 * state in which trips and assignments may be touched at all; {@link #CONFIRMED} is the planner's
 * commitment and locks the run and every trip in it; {@link #CANCELLED} discards a draft and
 * returns its orders to the eligible pool.
 *
 * <p>There is no execution/loading/dispatch state here on purpose - transport execution and the
 * EWM boundary are separate scopes (see the step brief and {@code CLAUDE.md}, "Deferred by
 * decision"). A confirmed run is terminal in V1.
 */
public enum PlanningRunStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}
