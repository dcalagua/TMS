package com.ebim.tms.planning.application;

import java.util.List;

/**
 * One planning run with its trips - the planning board. Each trip carries its capacity summary
 * and its counts, not its assignments: opening a board must stay two queries plus two batched
 * lookups, whatever the day's volume.
 */
public record PlanningRunDetailView(PlanningRunView run, List<TripView> trips) {
}
