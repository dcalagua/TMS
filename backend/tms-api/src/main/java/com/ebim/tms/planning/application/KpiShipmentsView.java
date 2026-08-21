package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.math.BigDecimal;
import java.util.Map;

/**
 * How many shipments the range produced, what became of them, and whether they left when they were
 * meant to ({@code docs/domain/KPIS_REPORTING_V1.md}, section "Shipments").
 *
 * @param trips           every shipment planned in the range, cancelled ones included. The honest
 *                        count of what was built, and the only one that ties back to the planning
 *                        board
 * @param tripsRun        {@code trips} minus the cancelled ones - the denominator of
 *                        {@link #completionPercent()}, because a shipment somebody withdrew is not
 *                        a shipment that failed to complete
 * @param tripsCancelled  withdrawn before they left. Reported rather than netted off silently: a
 *                        month with forty cancellations is a fact about the planning, not noise
 * @param tripsCompleted  closed out with every stop resolved ({@code Trip.complete})
 * @param byStatus        the whole lifecycle breakdown, so a screen can show the four numbers above
 *                        and still answer "how many are sitting in draft". States with nothing in
 *                        them are present with a zero, so a client never has to know the enum's
 *                        members to iterate it
 * @param departuresMeasured shipments carrying <em>both</em> a planned and an actual departure -
 *                        the only ones punctuality can be judged over. A shipment nobody recorded a
 *                        departure for is not an on-time departure, and letting it sit in the
 *                        denominator would make an operation look better the less it recorded
 * @param departuresLate  of those, the ones that left after the plan said they would. Strictly
 *                        later, with no grace period - see {@code DepartureDelay}
 * @param onTimeDeparturePercent {@code (measured - late) / measured}, or null when nothing was
 *                        measured. Null and not zero, for the reason {@code KpiRate} gives
 * @param completionPercent {@code tripsCompleted / tripsRun}, or null when nothing ran
 */
public record KpiShipmentsView(
        long trips,
        long tripsRun,
        long tripsCancelled,
        long tripsCompleted,
        Map<TripStatus, Long> byStatus,
        long departuresMeasured,
        long departuresLate,
        BigDecimal onTimeDeparturePercent,
        BigDecimal completionPercent) {
}
