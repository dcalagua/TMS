package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The board-row view of a trip: its vehicle and carrier resolved for display, its capacity
 * summary, and counts - never the assignments or stops themselves. The same list/detail split
 * {@code OrderView}/{@code OrderDetailView} uses, for the same reason: a planning board for a
 * 300-trip day must not fan out into one query per trip.
 */
public record TripView(
        UUID id,
        UUID planningRunId,
        int tripNumber,
        TripStatus status,
        UUID vehicleId,
        String vehicleCode,
        String vehicleLicensePlate,
        UUID carrierId,
        String carrierName,
        OffsetDateTime plannedDepartureAt,
        TripCapacityView capacity,
        int stopCount,
        long orderCount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
