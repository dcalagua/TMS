package com.ebim.tms.planning.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The control tower's overview response: one operating day, in one round trip.
 *
 * <p><b>Why the panels are capped and the table is paginated.</b> They answer different questions.
 * A panel answers "is there anything wrong", and the honest shape of that answer is the worst few
 * plus a total - a supervisor works the top of the list and the count tells them how deep it goes.
 * The table answers "show me the day", which is a list somebody scrolls, and that is what
 * pagination is for ({@code GET /monitoring/control-tower/trips}). Nothing here is truncated
 * silently: every capped list has its full size in {@link #summary}.
 *
 * @param date        the operating day every number here belongs to, in the company's own zone
 * @param generatedAt the instant the server judged the clock-dependent facts against - which trips
 *     are overdue, which stops have run past their window. Sent because those two change on their
 *     own: a tab left open for an hour is showing an hour-old verdict, and this is what lets it
 *     say so instead of looking current
 * @param workload    the fullest shipments of the day, worst first - "which vehicles are carrying
 *     the most"
 * @param openExceptions the newest unresolved problems; {@code summary.openExceptions()} is how
 *     many there are in total
 * @param outstandingStops the stops still to be worked on shipments that are out, most overdue
 *     first; {@code summary.outstandingStops()} is the total and
 *     {@code summary.stopsPastWindow()} how many of them are actually late
 * @param blockers shipments that <em>cannot depart</em> in their current state (JOB 12).
 *     {@code summary.blockedShipments()} is the total
 * @param advisories things worth knowing that <em>stop nothing</em> (JOB 23) - an open money
 *     question on a shipment, an arrival estimate that misses its window.
 *     <b>Deliberately a second list rather than more rows on {@code blockers}</b>: a blocker is a
 *     state that makes dispatch refuse, and once a panel has cried wolf about a rounding difference
 *     the shipment that genuinely cannot depart is one row among forty.
 *     {@code summary.openAdvisories()} is the total
 */
public record ControlTowerView(
        LocalDate date,
        OffsetDateTime generatedAt,
        ControlTowerSummaryView summary,
        List<ControlTowerWorkloadView> workload,
        List<ControlTowerExceptionView> openExceptions,
        List<ControlTowerStopView> outstandingStops,
        List<ControlTowerBlockerView> blockers,
        List<ControlTowerAdvisoryView> advisories) {

    public ControlTowerView {
        workload = List.copyOf(workload);
        openExceptions = List.copyOf(openExceptions);
        outstandingStops = List.copyOf(outstandingStops);
        blockers = List.copyOf(blockers);
        advisories = List.copyOf(advisories);
    }
}
