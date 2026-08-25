package com.ebim.tms.masterdata.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Answers "does this {@link Frequency} run on this date, and with what cutoff" - the one calendar
 * rule the eligibility service and any future planning code should share, instead of each
 * re-deriving it. Pure functions, so they are provable without a database (see
 * {@code FrequencyCalendarTest}).
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

    /**
     * The cutoff that actually applies on {@code date}:
     *
     * <pre>{@code effectiveCutoff = exception.cutoffTimeOverride ?? weeklyRule.cutoffTime}</pre>
     *
     * <p>A date exception may state its own cutoff (V24) - "open on 24/12, but closing at 11:00"
     * - and that is a fact about one date, so it wins over the weekly rule, exactly as
     * {@code serviceOverride} already wins in {@link #runsOn}. An exception with no override
     * expresses no opinion about the cutoff and falls back to the weekly rule, which is the
     * ordinary case: most blackouts and extra-service dates keep the usual closing time.
     *
     * <p>{@code null} means no cutoff applies at all - either the day has no configured weekly
     * rule (an extra-service date on a day the cadence never runs) or that rule leaves the field
     * empty, which is legal. Callers must not read a null here as "already closed".
     *
     * <p>Only sensible for a date that actually runs; a blackout cannot carry an override at all
     * (see {@link FrequencyException}'s constructor), so this returns the weekly rule's cutoff
     * for one, which no caller should be asking about.
     */
    public static LocalTime effectiveCutoff(Frequency frequency, LocalDate date, FrequencyException exceptionOnDate) {
        if (exceptionOnDate != null && exceptionOnDate.cutoffTimeOverride() != null) {
            return exceptionOnDate.cutoffTimeOverride();
        }
        FrequencyWeeklyRule weeklyRule = weeklyRuleFor(frequency, date);
        return weeklyRule == null ? null : weeklyRule.cutoffTime();
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
