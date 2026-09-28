package com.ebim.tms.orders.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * The moment the release window for a dispatch closes (ADR-014 section 3):
 *
 * <pre>releaseDeadline = (scheduledDispatchDate - leadTimeDays calendar days) at cutoffTime,
 *                   in the company's time zone</pre>
 *
 * <p>A missing cutoff means end of day - the deadline is {@code 00:00} of the next day, exclusive. A
 * missing lead time means zero. It is never computed from the order's {@code created_at} or the
 * ERP's {@code updated_at}: a release is judged against the dispatch, not against when somebody typed.
 *
 * @param at        the instant the window closes
 * @param endOfDay  true when no cutoff applied, so {@code at} is the next midnight and is itself
 *                  already too late
 */
public record ReleaseDeadline(OffsetDateTime at, boolean endOfDay) {

    public static ReleaseDeadline of(LocalDate dispatchDate, Integer leadTimeDays, LocalTime cutoffTime, ZoneId zone) {
        LocalDate lastDay = dispatchDate.minusDays(leadTimeDays == null ? 0 : leadTimeDays);
        if (cutoffTime == null) {
            return new ReleaseDeadline(lastDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), true);
        }
        return new ReleaseDeadline(lastDay.atTime(cutoffTime).atZone(zone).toOffsetDateTime(), false);
    }

    public boolean isMissedAt(OffsetDateTime now) {
        return endOfDay ? !now.isBefore(at) : now.isAfter(at);
    }

    /** The earlier of two deadlines - "both must permit", so the tighter one governs. Null-tolerant. */
    public static ReleaseDeadline earlier(ReleaseDeadline first, ReleaseDeadline second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.at.isBefore(first.at) ? second : first;
    }
}
