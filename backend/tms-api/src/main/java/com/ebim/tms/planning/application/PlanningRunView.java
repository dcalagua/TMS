package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.PlanningMode;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.PlanningRunStatus;
import com.ebim.tms.shared.reference.MasterReference;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The list-row view of a planning run: its origin resolved for display plus the two counts a
 * planner scans a list by, both computed in one grouped query per page rather than stored on the
 * run (see {@link com.ebim.tms.planning.domain.PlanningRun}'s class comment).
 */
public record PlanningRunView(
        UUID id,
        String planNumber,
        UUID originId,
        String originCode,
        String originName,
        LocalDate planningDate,
        PlanningMode mode,
        PlanningRunStatus status,
        String notes,
        long tripCount,
        long assignedOrderCount,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static PlanningRunView from(PlanningRun run, MasterReference origin, long tripCount, long assignedOrderCount) {
        return new PlanningRunView(run.id(), run.planNumber(), run.originId(),
                origin == null ? null : origin.code(), origin == null ? null : origin.name(), run.planningDate(),
                run.mode(), run.status(), run.notes(), tripCount, assignedOrderCount, run.confirmedAt(),
                run.cancelledAt(), run.cancelReason(), run.version(), run.createdAt(), run.updatedAt());
    }
}
