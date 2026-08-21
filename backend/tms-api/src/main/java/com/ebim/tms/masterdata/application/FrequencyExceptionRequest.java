package com.ebim.tms.masterdata.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * @param cutoffTimeOverride optional per-date replacement for the weekly rule's cutoff (V24).
 *     Omit it - the ordinary case - and the weekly rule for that day of week still decides. Only
 *     valid together with {@code serviceOverride = true}; a closed date has no cutoff, and
 *     {@code FrequencyService} rejects the combination with a 400 rather than letting the
 *     database CHECK surface as a 500. Bean validation cannot express that cross-field rule.
 */
public record FrequencyExceptionRequest(
        @NotNull LocalDate exceptionDate,
        @NotNull Boolean serviceOverride,
        LocalTime cutoffTimeOverride,
        @Size(max = 500) String note) {
}
