package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A trip as the carrier portal lists it (ADR-018). Plate and driver are the warehouse dispatch's -
 * what physically left - and fall back to the plan's when the dispatch did not say.
 */
public record PortalTripSummaryView(
        UUID tripId,
        String shipmentNumber,
        TripStatus status,
        LocalDate planningDate,
        OffsetDateTime dispatchedAt,
        String originName,
        String vehicleLicensePlate,
        String driverName,
        int stopCount,
        int documentCount,
        int pendingCount,
        int deliveredCount,
        int rejectedCount,
        int notDeliveredCount,
        int returnedCount) {
}
