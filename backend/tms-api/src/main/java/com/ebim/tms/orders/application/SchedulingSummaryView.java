package com.ebim.tms.orders.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The board's summary strip (ADR-014 section 8): the same filters as the table, counted by origin,
 * derived route and dispatch date.
 *
 * @param totals every order the filters match
 * @param groups one per origin, route and dispatch date, ordered by date, origin and route
 */
public record SchedulingSummaryView(Counts totals, List<Group> groups) {

    public SchedulingSummaryView {
        groups = List.copyOf(groups);
    }

    /**
     * @param released  already {@code READY_FOR_PLANNING}
     * @param withHolds with at least one active hold, blocking or not
     */
    public record Counts(long total, long eligible, long warning, long blocked, long withHolds, long released) {
    }

    /** {@code routeCode} is null for orders that resolve to no single route. */
    public record Group(UUID originId, String originCode, String originName, String routeCode, String routeName,
            LocalDate scheduledDispatchDate, Counts counts) {
    }
}
