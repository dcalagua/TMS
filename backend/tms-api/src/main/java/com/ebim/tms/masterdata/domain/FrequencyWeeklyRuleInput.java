package com.ebim.tms.masterdata.domain;

import java.time.LocalTime;

/**
 * The domain-facing shape {@link Frequency#replaceWeeklyRules(java.util.List, java.util.UUID)}
 * accepts, kept separate from {@code FrequencyRequest.WeeklyRuleRequest} (the API DTO) so the
 * domain package does not depend on the application/web layers (review chain rule). The caller
 * (@code FrequencyService}) is responsible for rejecting a list with a duplicate {@code
 * dayOfWeek} before calling in - this record does not validate itself.
 */
public record FrequencyWeeklyRuleInput(int dayOfWeek, boolean enabled, LocalTime cutoffTime, Integer leadTimeDays) {
}
