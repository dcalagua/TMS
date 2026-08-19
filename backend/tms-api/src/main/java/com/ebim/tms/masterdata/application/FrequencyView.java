package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.FrequencyWeeklyRule;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link Frequency}, kept separate from the JPA entity (review chain rule). */
public record FrequencyView(
        UUID id,
        String code,
        String name,
        String description,
        boolean active,
        java.util.List<WeeklyRuleView> weeklyRules,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static FrequencyView from(Frequency frequency) {
        return new FrequencyView(frequency.id(), frequency.code(), frequency.name(), frequency.description(),
                frequency.active(), frequency.weeklyRules().stream().map(WeeklyRuleView::from).toList(),
                frequency.createdAt(), frequency.updatedAt());
    }

    public record WeeklyRuleView(int dayOfWeek, boolean enabled, LocalTime cutoffTime, Integer leadTimeDays) {
        static WeeklyRuleView from(FrequencyWeeklyRule rule) {
            return new WeeklyRuleView(rule.dayOfWeek(), rule.enabled(), rule.cutoffTime(), rule.leadTimeDays());
        }
    }
}
