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
        FrequencyException blackout = new FrequencyException(UUID.randomUUID(), MONDAY, false, "Holiday", UUID.randomUUID());
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), MONDAY, blackout)).isFalse();
    }

    @Test
    @DisplayName("an extra-service exception overrides an otherwise-disabled/unconfigured day")
    void extraServiceExceptionOverridesDisabledDay() {
        LocalDate sunday = MONDAY.plusDays(6);
        FrequencyException extra =
                new FrequencyException(UUID.randomUUID(), sunday, true, "Peak season", UUID.randomUUID());
        assertThat(FrequencyCalendar.runsOn(weekdayFrequency(), sunday, extra)).isTrue();
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
