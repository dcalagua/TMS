package com.ebim.tms.masterdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.masterdata.domain.LocationEligibilityEvaluator.Candidate;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LocationEligibilityEvaluator} decides "can this location be serviced/dispatched on this
 * date" (job 03 brief) from already-resolved data, so it is proved here without a database - the
 * same reasoning {@code FrequencyCalendarTest} and {@code LocationModelTest} document.
 */
class LocationEligibilityEvaluatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);

    private static Frequency mondayOnlyFrequency() {
        Frequency frequency = new Frequency(UUID.randomUUID(), "MON", "Monday only", null, UUID.randomUUID());
        frequency.replaceWeeklyRules(
                List.of(new FrequencyWeeklyRuleInput(1, true, LocalTime.of(15, 0), 2)), UUID.randomUUID());
        return frequency;
    }

    private static Candidate candidate(Frequency frequency, LocalDate from, LocalDate to, boolean associationActive) {
        return candidate(frequency, from, to, associationActive, null);
    }

    private static Candidate candidate(Frequency frequency, LocalDate from, LocalDate to, boolean associationActive,
            FrequencyException exceptionOnDate) {
        LocationFrequency association =
                new LocationFrequency(UUID.randomUUID(), UUID.randomUUID(), frequency.id(), from, to, UUID.randomUUID());
        if (!associationActive) {
            association.deactivate(UUID.randomUUID());
        }
        return new Candidate(association, frequency, exceptionOnDate);
    }

    @Test
    @DisplayName("an inactive location is never eligible, regardless of its associations")
    void inactiveLocationIsNeverEligible() {
        Candidate candidate = candidate(mondayOnlyFrequency(), null, null, true);

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(false, List.of(candidate), MONDAY);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.reason()).contains("inactive");
    }

    @Test
    @DisplayName("no associations at all is not eligible")
    void noAssociationsIsNotEligible() {
        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(true, List.of(), MONDAY);

        assertThat(decision.eligible()).isFalse();
    }

    @Test
    @DisplayName("a matching, active, in-range association makes the location eligible with its frequency's metadata")
    void matchingActiveAssociationIsEligible() {
        Frequency frequency = mondayOnlyFrequency();
        Candidate candidate = candidate(frequency, null, null, true);

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(true, List.of(candidate), MONDAY);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.matchedFrequencyId()).isEqualTo(frequency.id());
        assertThat(decision.cutoffTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(decision.leadTimeDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("a date exception's cutoff override is the cutoff the decision reports")
    void exceptionCutoffOverrideReachesTheDecision() {
        Frequency frequency = mondayOnlyFrequency();
        FrequencyException earlyClose = new FrequencyException(
                UUID.randomUUID(), MONDAY, true, LocalTime.of(11, 0), "Closes early", UUID.randomUUID());

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(
                true, List.of(candidate(frequency, null, null, true, earlyClose)), MONDAY);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.cutoffTime()).isEqualTo(LocalTime.of(11, 0));
        // Lead time has no per-date override and keeps coming from the weekly rule: one date
        // closing earlier does not change how far ahead this location takes orders.
        assertThat(decision.leadTimeDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("an open exception that states no cutoff leaves the weekly rule's cutoff in the decision")
    void exceptionWithoutOverrideKeepsTheWeeklyCutoff() {
        FrequencyException extraRun =
                new FrequencyException(UUID.randomUUID(), MONDAY, true, null, "Extra run", UUID.randomUUID());

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(
                true, List.of(candidate(mondayOnlyFrequency(), null, null, true, extraRun)), MONDAY);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.cutoffTime()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("a date outside the association's effective range is not eligible even if the frequency would otherwise run")
    void dateOutsideEffectiveRangeIsNotEligible() {
        Candidate candidate = candidate(mondayOnlyFrequency(), MONDAY.plusMonths(1), null, true);

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(true, List.of(candidate), MONDAY);

        assertThat(decision.eligible()).isFalse();
    }

    @Test
    @DisplayName("a deactivated association is ignored even when its date range and frequency would otherwise match")
    void deactivatedAssociationIsIgnored() {
        Candidate candidate = candidate(mondayOnlyFrequency(), null, null, false);

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(true, List.of(candidate), MONDAY);

        assertThat(decision.eligible()).isFalse();
    }

    @Test
    @DisplayName("a location is eligible if ANY of several associated frequencies services the date")
    void eligibleIfAnyAssociatedFrequencyMatches() {
        Frequency mondayOnly = mondayOnlyFrequency();
        Frequency tuesdayOnly = new Frequency(UUID.randomUUID(), "TUE", "Tuesday only", null, UUID.randomUUID());
        tuesdayOnly.replaceWeeklyRules(
                List.of(new FrequencyWeeklyRuleInput(2, true, null, null)), UUID.randomUUID());

        List<Candidate> candidates = List.of(
                candidate(mondayOnly, null, null, true),
                candidate(tuesdayOnly, null, null, true));

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(true, candidates, TUESDAY);

        // Entities are unpersisted here, so ids are null on both frequencies - the metadata below
        // is what actually proves the match came from tuesdayOnly's Tuesday rule (no cutoff/lead
        // time configured) and not mondayOnly's Monday rule (15:00 / 2 days), which does not run
        // on a Tuesday at all.
        assertThat(decision.eligible()).isTrue();
        assertThat(decision.cutoffTime()).isNull();
        assertThat(decision.leadTimeDays()).isNull();
    }

    @Test
    @DisplayName("no associated frequency services the date is not eligible")
    void noMatchingFrequencyIsNotEligible() {
        Candidate candidate = candidate(mondayOnlyFrequency(), null, null, true);

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(true, List.of(candidate), TUESDAY);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.reason()).contains("No associated frequency");
    }
}
