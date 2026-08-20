package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.EligibilityDecision;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** API-facing view of an {@link EligibilityDecision} for one location and date. */
public record EligibilityView(
        LocalDate date, boolean eligible, String reason, UUID frequencyId, LocalTime cutoffTime, Integer leadTimeDays) {

    public static EligibilityView from(LocalDate date, EligibilityDecision decision) {
        return new EligibilityView(date, decision.eligible(), decision.reason(), decision.matchedFrequencyId(),
                decision.cutoffTime(), decision.leadTimeDays());
    }
}
