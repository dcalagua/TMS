package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.PlanningRunStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /planning/runs}, bound alongside
 * {@link com.ebim.tms.shared.api.PageQuery}. The date bounds form a range for the same reason
 * {@code OrderFilter}'s do: a planner asks "what did we plan this week", not "on this exact day".
 */
public record PlanningRunFilter(
        String planNumber,
        UUID originId,
        LocalDate planningDateFrom,
        LocalDate planningDateTo,
        PlanningRunStatus status) {
}
