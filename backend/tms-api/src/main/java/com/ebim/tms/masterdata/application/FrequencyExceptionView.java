package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.FrequencyException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link FrequencyException}, kept separate from the JPA entity. */
public record FrequencyExceptionView(
        UUID id, LocalDate exceptionDate, boolean serviceOverride, String note, OffsetDateTime createdAt) {

    public static FrequencyExceptionView from(FrequencyException exception) {
        return new FrequencyExceptionView(exception.id(), exception.exceptionDate(), exception.serviceOverride(),
                exception.note(), exception.createdAt());
    }
}
