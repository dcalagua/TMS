package com.ebim.tms.masterdata.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Domain/application service (job 03 brief) that answers "can this location be serviced/
 * dispatched on this date", from already-resolved data - no repository access, no Spring, so it
 * is fully provable without a database (see {@code LocationEligibilityEvaluatorTest}). The
 * Spring-managed orchestration that loads a location's associations, their frequencies and the
 * matching date exceptions lives in {@code LocationEligibilityService}, which calls this class.
 *
 * <p>Not an optimizer (out of scope per the job brief and the repository's deferred-by-decision
 * list): it makes one yes/no decision for one date, nothing more.
 */
public final class LocationEligibilityEvaluator {

    private LocationEligibilityEvaluator() {
    }

    /**
     * One resolved candidate: a {@link LocationFrequency} association together with its
     * {@link Frequency} and, if one exists, the {@link FrequencyException} for the date being
     * evaluated. {@code frequency} is never null - the caller drops a candidate whose frequency
     * failed to resolve (for example a stale id) before building this record.
     */
    public record Candidate(LocationFrequency association, Frequency frequency, FrequencyException exceptionOnDate) {
    }

    /**
     * A location may hold several {@link LocationFrequency} associations (job brief: "one or
     * multiple schedules per location"); it is eligible on {@code date} if <em>any</em> in-scope
     * association's frequency runs that date - a store served by two independent schedules only
     * needs one of them to be active for the date to be serviceable. Candidates are evaluated in
     * the order given, and the first match's metadata (cutoff/lead time) is returned; callers
     * that care about a specific ordering (for example "prefer the most recently created
     * schedule") should already have sorted {@code candidates} accordingly.
     */
    public static EligibilityDecision evaluate(boolean locationActive, List<Candidate> candidates, LocalDate date) {
        if (!locationActive) {
            return EligibilityDecision.notEligible("Location is inactive.");
        }

        List<Candidate> inScope = candidates.stream()
                .filter(candidate -> candidate.association().active() && candidate.association().coversDate(date))
                .toList();
        if (inScope.isEmpty()) {
            return EligibilityDecision.notEligible(
                    "No active service-calendar association covers this date.");
        }

        for (Candidate candidate : inScope) {
            Frequency frequency = candidate.frequency();
            if (FrequencyCalendar.runsOn(frequency, date, candidate.exceptionOnDate())) {
                FrequencyWeeklyRule matchedRule = FrequencyCalendar.weeklyRuleFor(frequency, date);
                // Cutoff comes from FrequencyCalendar, not straight off the weekly rule: a date
                // exception may replace it for this date alone (V24). Lead time has no such
                // override - it is a property of the cadence a customer plans against, and one
                // date moving its closing time does not change how far ahead orders are taken.
                return EligibilityDecision.eligible(frequency.id(),
                        FrequencyCalendar.effectiveCutoff(frequency, date, candidate.exceptionOnDate()),
                        matchedRule == null ? null : matchedRule.leadTimeDays());
            }
        }

        return EligibilityDecision.notEligible("No associated frequency services this date.");
    }
}
