package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.ShipmentDocumentState;
import com.ebim.tms.planning.domain.TripStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The tracking report's filter. Dates are days of dispatch in the company's zone, both inclusive;
 * with neither, today. A null field does not filter.
 */
public record ShipmentTrackingFilter(LocalDate dateFrom, LocalDate dateTo, UUID carrierId, TripStatus status,
        ShipmentDocumentState documents, String q) {
}
