package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The monitor's filter. Dates are calendar days of dispatch in the company's zone, both inclusive;
 * a null field does not filter.
 */
public record DocumentDeliveryFilter(LocalDate dateFrom, LocalDate dateTo, UUID carrierId,
        DocumentDeliveryStatus status, Boolean returned, String q) {
}
