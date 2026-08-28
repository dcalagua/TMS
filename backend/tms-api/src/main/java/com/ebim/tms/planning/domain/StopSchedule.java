package com.ebim.tms.planning.domain;

import java.time.OffsetDateTime;

/**
 * When the vehicle is expected at one stop (migration V43, ADR-011).
 *
 * <p><b>Every field may be null together, and that is a result rather than a gap.</b> A stop whose
 * incoming leg could not be measured has no estimate, and neither does any stop after it - see
 * {@link StopScheduleEngine}. {@link #unscheduled(int)} is that answer, said once.
 *
 * @param sequence     the stop this belongs to, as the planner ordered it
 * @param arrivalAt    when the vehicle is expected, or null when there is no estimate
 * @param departureAt  when it is expected to leave - arrival plus the site's service time, and plus
 *                     any wait for the window to open. Never earlier than {@code arrivalAt}
 * @param source       what the weakest leg feeding this stop was. Never upgrades along the chain
 * @param missesWindow whether the vehicle arrives after this stop's window closes. The arrival is
 *                     still reported as computed: moving it to make the window fit would turn a
 *                     schedule that does not work into one that appears to
 * @param waitMinutes  how long the vehicle waits for the window to open, zero when it does not.
 *                     Worth reporting on its own - a route that works only because a truck idles
 *                     two hours at stop one is a route somebody should look at
 */
public record StopSchedule(
        int sequence,
        OffsetDateTime arrivalAt,
        OffsetDateTime departureAt,
        EtaSource source,
        boolean missesWindow,
        long waitMinutes) {

    /** No estimate for this stop, because a leg on the way to it could not be measured. */
    public static StopSchedule unscheduled(int sequence) {
        return new StopSchedule(sequence, null, null, null, false, 0);
    }

    public boolean isScheduled() {
        return arrivalAt != null;
    }
}
