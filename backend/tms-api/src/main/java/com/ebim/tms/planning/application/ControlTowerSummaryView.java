package com.ebim.tms.planning.application;

/**
 * The control tower's KPI strip: one operating day, counted server-side.
 *
 * <p>Every field is the result of a {@code COUNT} against an index, not a number the browser
 * derived from a page of rows. That is the whole reason this record exists - a dashboard that
 * fetched the day's trips to count them would re-read the board on every refresh, and would be
 * wrong the moment the board was paginated.
 *
 * <p><b>What the strip is scoped by.</b> Company, and the day. Not the origin, carrier or status
 * the table below is filtered by - see {@code ControlTowerService}. The strip is the day's whole
 * picture; narrowing it would let a filtered-out shipment hide a problem from the one place that
 * exists to surface problems.
 *
 * <p>The counters are ordered the way an operator reads them: what is happening, what is wrong,
 * and what has not been dealt with yet.
 *
 * @param tripsDraft         planned for today and still not confirmed. Not a neutral figure late
 *                           in the day: a draft cannot be dispatched
 * @param tripsScheduled     confirmed or loaded and waiting - committed, and still here
 * @param tripsInTransit     out on the road right now
 * @param tripsCompleted     finished today
 * @param tripsCancelled     pulled. Counted rather than hidden, because "we planned 40 and
 *                           cancelled 9" is the fact behind a bad day
 * @param tripsDepartedLate  left after the plan said they would, by any margin - V1 has no grace
 *                           period, see {@code DepartureDelay}
 * @param tripsOverdue       were due to leave and have not; the only counter here that changes on
 *                           its own as the clock moves, which is why the response stamps the
 *                           instant it was judged against
 * @param openExceptions     problems raised against today's trips that nobody has closed out
 * @param outstandingStops   stops on trips that are out and still not resolved - the work left in
 *                           the field
 * @param stopsPastWindow    of those, the ones whose service window has already closed. A subset
 *                           of {@code outstandingStops}, never a separate population
 * @param ordersUnplanned    orders for this service date that are still assignable to nothing -
 *                           null, and not zero, when the caller does not hold
 *                           {@code orders.order:read}. Zero would be a claim about the backlog
 *                           that this response is not entitled to make
 */
public record ControlTowerSummaryView(
        long tripsDraft,
        long tripsScheduled,
        long tripsInTransit,
        long tripsCompleted,
        long tripsCancelled,
        long tripsDepartedLate,
        long tripsOverdue,
        long openExceptions,
        long outstandingStops,
        long stopsPastWindow,
        Long ordersUnplanned,
        /**
         * How many of today's shipments cannot depart in their current state (JOB 12).
         *
         * <p>The total behind {@code ControlTowerView.blockers}, which is capped like every other
         * panel. Zero is the ordinary reading and is worth showing as zero: "nothing is stuck" is a
         * fact a dispatcher wants stated, not inferred from an empty list.
         */
        long blockedShipments,
        /**
         * How many advisories today's shipments carry (JOB 23).
         *
         * <p>Counted separately from {@code blockedShipments} and never added to it. They answer
         * different questions - "is anything stuck" and "is anything worth knowing" - and a single
         * total would let three rounding differences read as three trucks that cannot leave.
         */
        long openAdvisories) {

    // No derived accessors here. A record's non-component methods are not part of what Jackson
    // puts on the wire (see PageResponse, whose totalPages/hasNext are likewise server-side only),
    // so a tripsDelayed() convenience would read as contract in Java and be absent in TypeScript.
    // The screen adds departedLate and overdue itself, and names both in the hint - which is also
    // the honest presentation, because the two are different phone calls.
}
