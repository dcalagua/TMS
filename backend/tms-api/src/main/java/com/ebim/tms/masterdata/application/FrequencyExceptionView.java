package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.FrequencyException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-facing view of a {@link FrequencyException}, kept separate from the JPA entity.
 *
 * @param cutoffTimeOverride {@code null} when this date does not replace the weekly rule's
 *     cutoff. Deliberately the stored value rather than the resolved one: this view describes an
 *     exception on its own, with no date context to resolve a weekly rule against. The resolved
 *     cutoff for a location and date is what {@code EligibilityView.cutoffTime} returns.
 */
public record FrequencyExceptionView(
        UUID id, LocalDate exceptionDate, boolean serviceOverride, LocalTime cutoffTimeOverride, String note,
        OffsetDateTime createdAt) {

    public static FrequencyExceptionView from(FrequencyException exception) {
        return new FrequencyExceptionView(exception.id(), exception.exceptionDate(), exception.serviceOverride(),
                exception.cutoffTimeOverride(), exception.note(), exception.createdAt());
    }
}
