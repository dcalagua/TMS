package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.LocationFrequency;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link LocationFrequency}, kept separate from the JPA entity. */
public record LocationFrequencyView(
        UUID id,
        UUID frequencyId,
        String frequencyCode,
        String frequencyName,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean active,
        OffsetDateTime createdAt) {

    /** {@code frequency} is {@code null} when the linked frequency no longer resolves in this company. */
    public static LocationFrequencyView from(LocationFrequency association, Frequency frequency) {
        return new LocationFrequencyView(association.id(), association.frequencyId(),
                frequency == null ? null : frequency.code(), frequency == null ? null : frequency.name(),
                association.effectiveFrom(), association.effectiveTo(), association.active(), association.createdAt());
    }
}
