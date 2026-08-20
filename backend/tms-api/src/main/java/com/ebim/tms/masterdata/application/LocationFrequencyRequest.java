package com.ebim.tms.masterdata.application;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** Associates a frequency with a location. {@code effectiveFrom}/{@code effectiveTo} are both optional. */
public record LocationFrequencyRequest(@NotNull UUID frequencyId, LocalDate effectiveFrom, LocalDate effectiveTo) {
}
