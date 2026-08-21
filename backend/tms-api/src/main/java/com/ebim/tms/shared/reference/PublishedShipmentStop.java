package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One ordered stop of a published shipment. {@code sequence} is always part of a contiguous 1..N
 * series - see {@code planning.domain.Trip#assertStopSequenceIntegrity}.
 *
 * @param latitude  the destination's current coordinate, or null when it has never been geocoded
 *                  - both or neither, never one alone (see {@code MasterReference#hasCoordinates})
 * @param longitude see {@code latitude}
 */
public record PublishedShipmentStop(
        UUID destinationId,
        int sequence,
        String locationCode,
        String locationName,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalTime serviceWindowStart,
        LocalTime serviceWindowEnd) {
}
