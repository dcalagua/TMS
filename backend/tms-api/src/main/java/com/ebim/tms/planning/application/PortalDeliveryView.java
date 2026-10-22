package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.TransportEventSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What the carrier sees of a recorded delivery (V70): the same row the office corrects, so a result
 * the office fixed shows here fixed, with {@code source} saying who recorded it last. The refused
 * amounts are null when no quantities were recorded.
 */
public record PortalDeliveryView(UUID id, DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
        String receiverDocument, String notes, TransportEventSource source, OffsetDateTime recordedAt,
        int evidenceCount, BigDecimal refusedWeightKg, BigDecimal refusedVolumeM3, BigDecimal refusedPallets) {
}
