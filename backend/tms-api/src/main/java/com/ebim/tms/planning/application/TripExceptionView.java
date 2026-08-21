package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripExceptionStatus;
import com.ebim.tms.planning.domain.TripExceptionType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One operational problem on a trip as the API reports it (migration V27).
 *
 * <p>Like {@link TransportEventView}, it names its stop by sequence and destination rather than by
 * id alone: "CUSTOMER_CLOSED at stop 4 - Supermercado Centro" is a sentence a supervisor can act
 * on, and a bare uuid is not.
 *
 * @param stopSequence the stop this is about, or null when the problem is the trip's
 * @param notes what was reported, free text. Mandatory only for {@code OTHER}, which says nothing
 *     on its own
 * @param resolutionNotes what was done about it, null while the exception is open
 */
public record TripExceptionView(
        UUID id,
        UUID tripId,
        UUID tripStopId,
        Integer stopSequence,
        String stopDestinationCode,
        String stopDestinationName,
        TripExceptionType exceptionType,
        TripExceptionStatus status,
        OffsetDateTime reportedAt,
        String notes,
        OffsetDateTime resolvedAt,
        String resolutionNotes) {
}
