package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of a state transition that a second planner could be racing: confirming or cancelling
 * a run, cancelling a trip.
 *
 * <p>{@code version} is mandatory and checked against the persisted row before anything changes -
 * rule 11 in {@code docs/database/DATA_MODEL.md} section 13, the pattern
 * {@code OrderService.requireCurrentVersion} established. Assignment operations deliberately do
 * <em>not</em> take a version: they are guarded by the trip's row lock and by the assignment
 * uniqueness invariant instead, so one planner assigning an order does not invalidate another
 * planner's open board - see {@code docs/domain/PLANNING_MANUAL_V1.md}, "Concurrency".
 *
 * @param reason free text kept for audit on a cancellation; ignored by confirm
 */
public record PlanningActionRequest(
        @NotNull(message = "is required") Long version,
        @Size(max = 500, message = "must be at most 500 characters") String reason,
        /**
         * Which planning engine to run (JOB 05). Null means {@code PlanningEngines.DEFAULT_ENGINE},
         * which is still {@code HEURISTIC_V1} - so every existing caller keeps the proposals it has
         * always got, and choosing {@code PLANNING_V2} is a deliberate act rather than a silent
         * upgrade of what a familiar button does.
         */
        @Size(max = 40, message = "must be at most 40 characters") String engine) {

    /** The pre-JOB-05 shape: the default engine. */
    public PlanningActionRequest(Long version, String reason) {
        this(version, reason, null);
    }
}
