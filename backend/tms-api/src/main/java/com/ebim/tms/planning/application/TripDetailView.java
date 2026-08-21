package com.ebim.tms.planning.application;

import java.util.List;

/**
 * One trip with everything on it: the board row, its active assignments, its ordered stops and the
 * operational problems raised against it. Returned by every trip endpoint except the board itself
 * - see {@link TripView} for why the two shapes differ.
 *
 * <p>The <em>timeline</em> is deliberately not here. Exceptions are a short list that gates what a
 * dispatcher can do next (and that a screen must show beside the stops), while the event log grows
 * without bound over a long day and is read on its own terms - so it has its own endpoint,
 * {@code GET /planning/trips/{id}/events}, and is not re-sent by every mutation that happens to
 * return a trip.
 *
 * @param exceptions newest first, open and resolved together: "what went wrong on this trip" is
 *     the question, and hiding the resolved ones would answer a different one.
 * @param deliveries what was handed over at each stop (migration V28), flat rather than nested in
 *     the stops - see {@link OrderDeliveryView}. Bounded by the trip's order count, so it belongs
 *     here for the same reason the assignments do and the timeline does not: it is a short list a
 *     screen needs together with the stops it annotates. An assigned order with no entry here has
 *     simply not been recorded yet.
 */
public record TripDetailView(
        TripView trip,
        List<TripAssignmentView> assignments,
        List<TripStopView> stops,
        List<TripExceptionView> exceptions,
        List<OrderDeliveryView> deliveries) {
}
