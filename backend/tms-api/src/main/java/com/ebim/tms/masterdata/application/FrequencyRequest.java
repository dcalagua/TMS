package com.ebim.tms.masterdata.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/**
 * Create and update share one shape. {@code weeklyRules} is the whole cadence, not a delta: the
 * V1 UI renders a fixed Monday-Sunday grid and sends every row it shows (checked or not), and
 * {@code FrequencyService} replaces the persisted set with exactly what is sent - see
 * {@code com.ebim.tms.masterdata.domain.Frequency#replaceWeeklyRules}. At most 7 entries (one
 * per day); {@code FrequencyService} additionally rejects a duplicate {@code dayOfWeek}, which
 * bean validation alone cannot express.
 */
public record FrequencyRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description,
        @NotNull @Size(max = 7) List<@Valid WeeklyRuleRequest> weeklyRules) {

    public record WeeklyRuleRequest(
            @NotNull @Min(1) @Max(7) Integer dayOfWeek,
            boolean enabled,
            LocalTime cutoffTime,
            @Min(value = 0, message = "must be zero or greater") Integer leadTimeDays) {
    }
}
