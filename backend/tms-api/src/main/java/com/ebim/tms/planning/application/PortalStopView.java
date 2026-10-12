package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A stop of a portal trip with the documents delivered there. A final entry with a null
 * {@code stopId} holds documents whose orders matched no stop. The coordinates let the carrier open
 * navigation (ADR-019); null where the destination has none.
 */
public record PortalStopView(UUID stopId, Integer sequence, String destinationName, String address,
        BigDecimal latitude, BigDecimal longitude, List<PortalDocumentView> documents) {
}
