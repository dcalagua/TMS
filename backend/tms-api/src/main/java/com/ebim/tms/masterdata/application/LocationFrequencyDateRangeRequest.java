package com.ebim.tms.masterdata.application;

import java.time.LocalDate;

/**
 * Update shape for an existing association: only the effective date range can be edited.
 * {@code frequencyId} cannot be changed once created - delete the association and create a new
 * one, the same way a route's stop list is replaced wholesale rather than patched (see
 * {@code Route#replaceStops}), except here there is no whole-collection replace because each
 * association is an independent calendar fact, exactly like {@code FrequencyException}.
 */
public record LocationFrequencyDateRangeRequest(LocalDate effectiveFrom, LocalDate effectiveTo) {
}
