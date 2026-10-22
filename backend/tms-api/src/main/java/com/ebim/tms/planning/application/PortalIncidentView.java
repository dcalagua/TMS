package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripExceptionStatus;
import com.ebim.tms.planning.domain.TripExceptionType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A problem on a portal trip (V70): reported by the carrier or by the office, open or resolved. */
public record PortalIncidentView(UUID id, UUID tripStopId, Integer stopSequence, TripExceptionType exceptionType,
        TripExceptionStatus status, OffsetDateTime reportedAt, String notes, OffsetDateTime resolvedAt) {
}
