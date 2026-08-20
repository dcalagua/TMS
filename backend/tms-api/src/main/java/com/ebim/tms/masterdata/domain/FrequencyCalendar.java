package com.ebim.tms.masterdata.domain;

import java.time.LocalDate;

/**
 * Answers "does this {@link Frequency} run on this date" - the one calendar rule the eligibility
 * service and any future planning code should share, instead of each re-deriving it. A pure
 * function so it is provable without a database (see {@code FrequencyCalendarTest}).
 *
 * <p>Precedence, matching how {@code FrequencyService} already treats the two child tables: a
 * date exception is a specific, deliberate override and always wins over the weekly cadence,
 * exactly as {@code tms.frequency_exception}'s own comment says ("an extra service date or a
 * blackout"). Absent an exception, the weekly rule for that ISO day of week decides - a day with
 * no configured row, or a configured-but-disabled row, both mean "does not run".
 */
public final class FrequencyCalendar {

    private FrequencyCalendar() {
    }

    /**
     * @param exceptionOnDate the {@link FrequencyException} for {@code date} on this frequency,
     *     if one exists - the caller resolves it (it is not a JPA relationship of
     *     {@link Frequency}, see {@code FrequencyException}'s own class comment).
     */
    public static boolean runsOn(Frequency frequency, LocalDate date, FrequencyException exceptionOnDate) {
        if (!frequency.active()) {
            return false;
        }
        if (exceptionOnDate != null) {
            return exceptionOnDate.serviceOverride();
        }
        int isoDayOfWeek = date.getDayOfWeek().getValue();
        return frequency.weeklyRules().stream()
                .filter(rule -> rule.dayOfWeek() == isoDayOfWeek)
                .findFirst()
                .map(FrequencyWeeklyRule::enabled)
                .orElse(false);
    }

    /** The configured weekly rule for {@code date}'s ISO day of week, or {@code null} if none exists. */
    public static FrequencyWeeklyRule weeklyRuleFor(Frequency frequency, LocalDate date) {
        int isoDayOfWeek = date.getDayOfWeek().getValue();
        return frequency.weeklyRules().stream()
                .filter(rule -> rule.dayOfWeek() == isoDayOfWeek)
                .findFirst()
                .orElse(null);
    }
}
