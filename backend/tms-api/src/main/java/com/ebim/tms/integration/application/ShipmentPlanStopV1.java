package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One ordered stop in the external {@code ShipmentPlan V1} contract. {@code sequence} is always
 * part of a contiguous 1..N series - see {@code planning.domain.Trip#assertStopSequenceIntegrity}.
 *
 * @param latitude  the location's current coordinate, or null when it has never been geocoded -
 *                  both or neither, never one alone
 * @param longitude see {@code latitude}
 */
public record ShipmentPlanStopV1(
        UUID locationId,
        int sequence,
        String locationCode,
        String locationName,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalTime serviceWindowStart,
        LocalTime serviceWindowEnd) {
}
