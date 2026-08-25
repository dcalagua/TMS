package com.ebim.tms.masterdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FrequencyCalendar} holds without a database, so it is proved on a machine where Docker
 * is unavailable too - the same reasoning {@code LocationModelTest} documents for the canonical
 * Location model.
 */
class FrequencyCalendarTest {

    // A Monday, chosen so shifting by day-of-week-1 lands on the matching weekday deterministically.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    private static Frequency weekdayFrequency() {
        Frequency frequency = new Frequency(UUID.randomUUID(), "WEEKDAYS", "Weekdays", null, UUID.randomUUID());
        frequency.replaceWeeklyRules(List.of(
                new FrequencyWeeklyRuleInput(1, true, LocalTime.of(14, 0), 1),
                new FrequencyWeeklyRuleInput(2, true, LocalTime.of(14, 0), 1),
                new FrequencyWeeklyRuleInput(3, true, LocalTime.of(14, 0), 1),
                new FrequencyWeeklyRuleInput(4, true, LocalTime.of(14, 0), 1),
                new FrequencyWeeklyRuleInput(5, true, LocalTime.of(14, 0), 1),
                // Saturday configured but disabled - distinct from "not configured at all".
                new FrequencyWeeklyRuleInput(6, false, null, null)),
                UUID.randomUUID());
        return frequency;
    }

    @Test
    @DisplayName("a day with an enabled weekly rule runs")
    void enabledWeekdayRuns() {
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), MONDAY, null)).isTrue();
    }

    @Test
    @DisplayName("a day with no configured row does not run")
    void unconfiguredDayDoesNotRun() {
        LocalDate sunday = MONDAY.plusDays(6);
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), sunday, null)).isFalse();
    }

    @Test
    @DisplayName("a day configured but disabled does not run")
    void disabledDayDoesNotRun() {
        LocalDate saturday = MONDAY.plusDays(5);
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), saturday, null)).isFalse();
    }

    @Test
    @DisplayName("an inactive frequency never runs, even on an otherwise-enabled day")
    void inactiveFrequencyNeverRuns() {
        Frequency frequency = weekdayFrequency();
        frequency.deactivate(UUID.randomUUID());
        assertThat(FrequencyCalendar.runsOn(frequency, MONDAY, null)).isFalse();
    }

    @Test
    @DisplayName("a blackout exception overrides an otherwise-enabled weekly rule")
    void blackoutExceptionOverridesEnabledDay() {
        FrequencyException blackout =
                new FrequencyException(UUID.randomUUID(), MONDAY, false, null, "Holiday", UUID.randomUUID());
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), MONDAY, blackout)).isFalse();
    }

    @Test
    @DisplayName("an extra-service exception overrides an otherwise-disabled/unconfigured day")
    void extraServiceExceptionOverridesDisabledDay() {
        LocalDate sunday = MONDAY.plusDays(6);
        FrequencyException extra =
                new FrequencyException(UUID.randomUUID(), sunday, true, null, "Peak season", UUID.randomUUID());
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), sunday, extra)).isTrue();
    }

    @Test
    @DisplayName("with no exception at all, the cutoff is the weekly rule's")
    void cutoffFallsBackToTheWeeklyRule() {
        assertThat(FrequencyCalendar.effectiveCutoff(weekdayFrequency(), MONDAY, null))
                .isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("an exception that states no cutoff leaves the weekly rule's cutoff alone")
    void exceptionWithoutOverrideKeepsTheWeeklyCutoff() {
        FrequencyException openWithoutCutoff =
                new FrequencyException(UUID.randomUUID(), MONDAY, true, null, "Extra run", UUID.randomUUID());

        assertThat(FrequencyCalendar.effectiveCutoff(weekdayFrequency(), MONDAY, openWithoutCutoff))
                .isEqualTo(LocalTime.of(14, 0));
    }

    /** The worked example from the V24 migration header: 24/12 open, but closing early. */
    @Test
    @DisplayName("an exception's cutoff override wins over the weekly rule for that date only")
    void cutoffOverrideWinsForThatDate() {
        Frequency frequency = weekdayFrequency();
        LocalDate christmasEve = MONDAY.plusDays(2);
        FrequencyException earlyClose = new FrequencyException(
                UUID.randomUUID(), christmasEve, true, LocalTime.of(11, 0), "Christmas Eve", UUID.randomUUID());

        assertThat(FrequencyCalendar.effectiveCutoff(frequency, christmasEve, earlyClose))
                .isEqualTo(LocalTime.of(11, 0));
        // The neighbouring Wednesday of any other week is untouched - that is the whole point of
        // putting the override on the date instead of editing the weekly rule.
        assertThat(FrequencyCalendar.effectiveCutoff(frequency, christmasEve.plusWeeks(1), null))
                .isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("an extra-service date on an unconfigured day has no cutoff unless the exception states one")
    void extraServiceDateHasNoCutoffOfItsOwn() {
        Frequency frequency = weekdayFrequency();
        LocalDate sunday = MONDAY.plusDays(6);
        FrequencyException extra =
                new FrequencyException(UUID.randomUUID(), sunday, true, null, "Peak season", UUID.randomUUID());
        FrequencyException extraClosingAtNoon = new FrequencyException(
                UUID.randomUUID(), sunday, true, LocalTime.of(12, 0), "Peak season", UUID.randomUUID());

        // Sunday has no weekly rule to fall back to: null means "no cutoff applies", never
        // "already closed".
        assertThat(FrequencyCalendar.effectiveCutoff(frequency, sunday, extra)).isNull();
        assertThat(FrequencyCalendar.effectiveCutoff(frequency, sunday, extraClosingAtNoon))
                .isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("weeklyRuleFor returns the configured row for that day, or null when none exists")
    void weeklyRuleForResolvesTheMatchingDay() {
        Frequency frequency = weekdayFrequency();

        FrequencyWeeklyRule monday = FrequencyCalendar.weeklyRuleFor(frequency, MONDAY);
        FrequencyWeeklyRule sunday = FrequencyCalendar.weeklyRuleFor(frequency, MONDAY.plusDays(6));

        assertThat(monday).isNotNull();
        assertThat(monday.cutoffTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(monday.leadTimeDays()).isEqualTo(1);
        assertThat(sunday).isNull();
    }
}
