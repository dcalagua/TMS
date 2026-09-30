package com.ebim.tms.shared.reference;

import java.time.LocalTime;

/**
 * What one service calendar says about one date (ADR-014 section 5): whether it is configured at
 * all, whether it serves the date, and - when it does - through which frequency and with what
 * cutoff and lead time.
 *
 * <p>{@code configured = false} is not a refusal. A destination with no calendar, or a route with
 * no frequency, has said nothing, and silence stays serviceable ({@link ServiceCalendarPort}).
 *
 * @param frequencyCode the frequency that serves the date (the first that does, for a destination
 *                      with several), or a configured one that does not, for display
 * @param cutoffTime    {@code FrequencyCalendar.effectiveCutoff}: an exception's override wins over
 *                      the weekly rule; null means end of day
 * @param leadTimeDays  the weekly rule's lead time; null means zero
 */
public record CalendarVerdict(boolean configured, boolean runs, String frequencyCode, LocalTime cutoffTime,
        Integer leadTimeDays) {

    public static final CalendarVerdict NOT_CONFIGURED = new CalendarVerdict(false, true, null, null, null);

    public static CalendarVerdict running(String frequencyCode, LocalTime cutoffTime, Integer leadTimeDays) {
        return new CalendarVerdict(true, true, frequencyCode, cutoffTime, leadTimeDays);
    }

    public static CalendarVerdict notRunning(String frequencyCode) {
        return new CalendarVerdict(true, false, frequencyCode, null, null);
    }
}
