package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One stop of a trip as the API reports it: its position, the destination resolved for display
 * and for a map, the service window envelope of the orders delivered there and how many of them
 * there are.
 *
 * <p>{@code sequence} is always part of a contiguous 1..N series over the trip's stops -
 * {@code Trip.assertStopSequenceIntegrity} refuses to persist anything else - so a client may
 * render "stop 3 of 7" without checking, and a map may number its markers from this field
 * directly.
 *
 * @param latitude  the destination's current coordinate, or null when it has never been geocoded.
 *                  Read live from the master rather than snapshotted onto the stop: a corrected
 *                  store coordinate must reach an open plan immediately, and a frozen wrong one
 *                  would be undetectable. See migration V19's header.
 * @param longitude see {@code latitude}; the two are always both present or both null
 */
public record TripStopView(
        UUID id,
        int sequence,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalTime serviceWindowStart,
        LocalTime serviceWindowEnd,
        long orderCount) {
}
