package com.ebim.tms.masterdata.domain;

import java.time.LocalTime;
import java.util.UUID;

/**
 * The result of {@link LocationEligibilityEvaluator#evaluate}: can a location be serviced/
 * dispatched on the evaluated date, and if so through which frequency and with what cutoff/lead
 * time metadata a planner would want to see alongside the decision.
 */
public record EligibilityDecision(
        boolean eligible, String reason, UUID matchedFrequencyId, LocalTime cutoffTime, Integer leadTimeDays) {

    public static EligibilityDecision notEligible(String reason) {
        return new EligibilityDecision(false, reason, null, null, null);
    }

    public static EligibilityDecision eligible(UUID frequencyId, LocalTime cutoffTime, Integer leadTimeDays) {
        return new EligibilityDecision(
                true, "A location-associated frequency services this date.", frequencyId, cutoffTime, leadTimeDays);
    }
}
