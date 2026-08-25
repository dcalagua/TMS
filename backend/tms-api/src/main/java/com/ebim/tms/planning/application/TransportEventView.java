package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TransportEventSource;
import com.ebim.tms.planning.domain.TransportEventType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of a trip's operational timeline as the API reports it (migration V27).
 *
 * <p>The stop is reported by <em>sequence and destination name</em> as well as by id: a timeline
 * that said "ARRIVED_AT_STOP at 11:04" and left the reader to join two lists to find out where
 * would be a log nobody reads.
 *
 * @param stopSequence the stop's position, or null for a trip-level entry
 * @param actorName who recorded it - the operator's email address, or the machine label of an
 *     integration. Taken from the event row itself, which snapshotted it: an operator who later
 *     changes their address keeps the one they acted under, which is what a log should say.
 * @param recordedAt when the entry was written, against {@code eventTime}'s when-it-happened. Both
 *     are reported because the gap between them is the interesting part - an arrival backdated
 *     six hours is a different fact from one typed as it happened.
 * @param metadata the event's compact JSON detail, verbatim, or null. Passed through as a string
 *     rather than re-parsed into a map: nothing in the UI reads inside it, and re-serialising it
 *     would only create a second shape for the same bytes.
 */
public record TransportEventView(
        UUID id,
        UUID tripId,
        UUID tripStopId,
        Integer stopSequence,
        String stopDestinationCode,
        String stopDestinationName,
        TransportEventType eventType,
        OffsetDateTime eventTime,
        OffsetDateTime recordedAt,
        TransportEventSource source,
        String actorName,
        String notes,
        String metadata) {
}
