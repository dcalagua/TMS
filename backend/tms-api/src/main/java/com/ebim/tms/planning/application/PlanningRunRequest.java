package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything a planner supplies to open a planning run. The company is not here and never will
 * be - it comes from the resolved {@code CompanyScope}, like every other write in TMS.
 *
 * <p>{@code mode} is absent on purpose too: V1 creates {@code MANUAL} runs only, and an automatic
 * mode will arrive with the machinery that produces it, not as a flag a client may set.
 */
public record PlanningRunRequest(
        @NotNull(message = "is required") UUID originId,
        @NotNull(message = "is required") LocalDate planningDate,
        @Size(max = 2000, message = "must be at most 2000 characters") String notes) {
}
