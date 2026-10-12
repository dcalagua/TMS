package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One dispatched shipment in the tracking report (ADR-019 section 2): what the warehouse says left -
 * reference, time, plate and driver, verification against the plan - with its stop progress and its
 * documents (ADR-018). Plate and driver fall back to the plan's when the dispatch did not name them.
 */
public record ShipmentTrackingRowView(
        UUID tripId,
        String shipmentNumber,
        TripStatus tripStatus,
        LocalDate planningDate,
        UUID carrierId,
        String carrierName,
        String dispatchReference,
        OffsetDateTime dispatchedAt,
        String dispatchVerification,
        String vehicleLicensePlate,
        String driverName,
        String originName,
        BigDecimal originLatitude,
        BigDecimal originLongitude,
        int stopsTotal,
        int stopsResolved,
        int documentCount,
        int pendingCount,
        int deliveredCount,
        int rejectedCount,
        int notDeliveredCount,
        int returnedCount,
        int inconsistentCount,
        OffsetDateTime lastReportAt,
        List<ShipmentTrackingStopView> stops) {
}
