package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.StopExecutionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A stop of a portal trip with the documents delivered there. A final entry with a null
 * {@code stopId} holds documents whose orders matched no stop. The coordinates let the carrier open
 * navigation (ADR-019); null where the destination has none.
 *
 * <p>Since V70 a stop also carries its execution - where the vehicle is in it, and which moves the
 * stop's own transition table allows next (the portal offers arrive, start service, complete and
 * fail; skipping is the office's decision) - and the orders delivered there, each with the result
 * recorded for it. All execution fields are null on the trailing group without a stop.
 */
public record PortalStopView(UUID stopId, Integer sequence, String destinationName, String address,
        BigDecimal latitude, BigDecimal longitude, List<PortalDocumentView> documents,
        StopExecutionStatus executionStatus, Set<StopExecutionStatus> allowedExecutionTransitions,
        OffsetDateTime actualArrivalAt, OffsetDateTime serviceStartedAt, OffsetDateTime actualDepartureAt,
        List<PortalStopOrderView> orders) {
}
