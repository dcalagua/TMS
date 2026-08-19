package com.ebim.tms.planning.application;

import java.time.LocalTime;
import java.util.UUID;

/**
 * One stop of a trip as the API reports it: its position, the destination resolved for display,
 * the service window envelope of the orders delivered there and how many of them there are.
 */
public record TripStopView(
        UUID id,
        int sequence,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        LocalTime serviceWindowStart,
        LocalTime serviceWindowEnd,
        long orderCount) {
}
