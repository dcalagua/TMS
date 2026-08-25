package com.ebim.tms.planning.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * A stop's planned service window resolved to a real instant, and how far past it the stop is -
 * the second half of the control tower's delay vocabulary, beside {@link DepartureDelay}.
 *
 * <p><b>Why this needs a rule at all.</b> {@link TripStop#serviceWindowEnd()} is a
 * {@link LocalTime} with no date on it, because a window is "before 14:00", a property of the
 * destination's day rather than of any instant. {@link TripStop#actualArrivalAt()} is an
 * {@link OffsetDateTime}, because an arrival is an instant. Comparing the two means deciding which
 * day and which zone the local time belongs to, and there is exactly one defensible answer: the
 * planning date of the stop's trip, in the company's own zone - the same choice
 * {@code ShipmentTimeRules} makes for planned departures, and for the same reason. A depot in
 * {@code America/Lima} whose window closes at 14:00 means 14:00 in Lima; judged in UTC every
 * afternoon window in the country would look breached.
 *
 * <p><b>Planned versus actual again.</b> Where the vehicle has arrived, lateness is measured
 * against the arrival, which is a recorded fact and never changes afterwards. Where it has not,
 * lateness is measured against the current instant, which is the statement "this has not happened
 * and was due" - the same fallback {@link DepartureDelay} uses, and the same non-heuristic. A stop
 * that was closed out without ever recording an arrival ({@link StopExecutionStatus#SKIPPED}) is
 * reported as not measurable rather than assumed on time or assumed late: nobody went, so there is
 * no lateness to state.
 *
 * <p>A window is a same-day interval on the planning date. TMS has never modelled an overnight
 * service window - {@code Trip.syncStops} copies the envelope of the day's requested order
 * windows, which are themselves same-day - so one is not invented here.
 *
 * @param endsAt           the instant the window closes, or null when the stop has no window; a
 *                         stop with no window can never be late, which is a real answer and not a
 *                         missing one
 * @param minutesPastWindow how many minutes past {@code endsAt} the stop is - measured to the
 *                         arrival when there is one and to {@code now} when the vehicle is still
 *                         expected - or null when it is inside its window, has no window, or was
 *                         never attempted
 */
public record StopServiceWindow(OffsetDateTime endsAt, Long minutesPastWindow) {

    /** The answer for a stop that has no planned window: nothing to be late against. */
    public static final StopServiceWindow NONE = new StopServiceWindow(null, null);

    /**
     * @param planningDate the date of the stop's trip - a stop carries no date of its own
     * @param zone         the company's zone, from {@code CompanyScope.zoneId()}
     * @param now          the instant an un-served stop is judged against, passed in so every row
     *                     of one response is judged against the same clock
     */
    public static StopServiceWindow of(LocalDate planningDate, LocalTime windowEnd, ZoneId zone,
            StopExecutionStatus executionStatus, OffsetDateTime actualArrivalAt, OffsetDateTime now) {
        if (windowEnd == null) {
            return NONE;
        }
        OffsetDateTime endsAt = planningDate.atTime(windowEnd).atZone(zone).toOffsetDateTime();
        OffsetDateTime measuredAt = measurementInstant(executionStatus, actualArrivalAt, now);
        if (measuredAt == null || !measuredAt.isAfter(endsAt)) {
            return new StopServiceWindow(endsAt, null);
        }
        return new StopServiceWindow(endsAt, Duration.between(endsAt, measuredAt).toMinutes());
    }

    /** Whether this stop has run past the time it was supposed to be served by. */
    public boolean isPastWindow() {
        return minutesPastWindow != null;
    }

    /**
     * The instant lateness is measured to: the arrival when the vehicle got there, the current
     * time while it is still expected, and nothing at all for a stop that was resolved without
     * anybody arriving.
     */
    private static OffsetDateTime measurementInstant(
            StopExecutionStatus executionStatus, OffsetDateTime actualArrivalAt, OffsetDateTime now) {
        if (actualArrivalAt != null) {
            return actualArrivalAt;
        }
        return executionStatus.isOutstanding() ? now : null;
    }
}
