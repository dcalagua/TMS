package com.ebim.tms.masterdata.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * "Optimizar paradas" (ADR-030): the origin and the stops as the route form has them right now.
 * Not a route id, because the button works while a route is being created and nothing is saved
 * yet - and on an existing route it must optimise what the planner is editing, not what was saved.
 *
 * @param destinationIds the stops in their current order; the proposal is measured against it
 */
public record RouteStopOptimizationRequest(
        @NotNull UUID originId,
        @NotNull @Size(min = 2, max = RouteStopOptimizationService.MAX_STOPS) List<@NotNull UUID> destinationIds) {
}
