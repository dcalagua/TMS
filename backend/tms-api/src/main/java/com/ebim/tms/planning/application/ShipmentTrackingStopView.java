package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A stop of a tracked shipment, with where it is and how its documents stand - by state, so a map
 * can colour the stop that had the rejection and not every stop of the shipment.
 */
public record ShipmentTrackingStopView(UUID stopId, int sequence, String destinationName, String address,
        BigDecimal latitude, BigDecimal longitude, String executionStatus, int documentCount, int pendingCount,
        int deliveredCount, int rejectedCount, int notDeliveredCount) {
}
