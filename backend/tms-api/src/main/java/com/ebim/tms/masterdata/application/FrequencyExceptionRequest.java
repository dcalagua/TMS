package com.ebim.tms.masterdata.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record FrequencyExceptionRequest(
        @NotNull LocalDate exceptionDate,
        @NotNull Boolean serviceOverride,
        @Size(max = 500) String note) {
}
