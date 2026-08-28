package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.EtaSource;
import com.ebim.tms.planning.domain.StopExecutionStatus;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One stop of a trip as the API reports it: its position, the destination resolved for display
 * and for a map, the service window envelope of the orders delivered there and how many of them
 * there are - plus, since migration V27, what actually happened there.
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
 * @param address   the destination's current address line, or null when it has none. Also read
 *                  live from the master, for the same reason as the coordinates.
 * @param allowedExecutionTransitions the outcomes this stop may still move to, decided
 *                  server-side from {@link StopExecutionStatus}'s transition table. A client
 *                  renders the buttons in this set and derives nothing from
 *                  {@code executionStatus} itself - the same contract {@code TripView} states for
 *                  the trip's own lifecycle, and for the same reason: the rule has one home.
 *                  Empty for a stop whose trip is not on the road, because a stop cannot be
 *                  worked before its vehicle leaves.
 * @param dwellMinutes how long the vehicle was at the stop, in whole minutes, or null until both
 *                  ends of it are known. Derived rather than stored - the two instants are the
 *                  facts, and this is one reading of them.
 */
public record TripStopView(
        UUID id,
        int sequence,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        BigDecimal latitude,
        BigDecimal longitude,
        String address,
        LocalTime serviceWindowStart,
        LocalTime serviceWindowEnd,
        long orderCount,
        StopExecutionStatus executionStatus,
        Set<StopExecutionStatus> allowedExecutionTransitions,
        OffsetDateTime actualArrivalAt,
        OffsetDateTime serviceStartedAt,
        OffsetDateTime actualDepartureAt,
        String executionNotes,
        Long dwellMinutes,
        int openExceptionCount,
        /**
         * When the vehicle is expected here (migration V43). Null means <b>no estimate</b> - a leg
         * on the way could not be measured, so this stop and every stop after it have none. A
         * board must render the gap rather than fill it: a plausible arrival time that is wrong
         * looks exactly like a right one.
         */
        OffsetDateTime etaArrivalAt,
        OffsetDateTime etaDepartureAt,
        /**
         * What the weakest leg feeding this stop was measured over. {@code FALLBACK} means at least
         * one leg was a straight line - the estimate is still useful and is not the same claim as a
         * measured road, and the screen says which.
         */
        EtaSource etaSource,
        OffsetDateTime etaCalculatedAt,
        /** The schedule has the vehicle arriving after this stop's window closes. */
        boolean etaMissesWindow) {
}
