package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.TransportEventSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the carrier sees of a recorded delivery (V70): the same row the office corrects, so a result
 * the office fixed shows here fixed, with {@code source} saying who recorded it last. The refused
 * amounts are null when no quantities were recorded. {@code refusedLines} (V75) lists the products
 * that came back, empty when none did or nobody said which.
 */
public record PortalDeliveryView(UUID id, DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
        String receiverDocument, String notes, TransportEventSource source, OffsetDateTime recordedAt,
        int evidenceCount, BigDecimal refusedWeightKg, BigDecimal refusedVolumeM3, BigDecimal refusedPallets,
        List<RefusedLineView> refusedLines) {

    /** One product the customer refused, in the line's own unit. */
    public record RefusedLineView(UUID orderLineId, Integer lineNumber, String materialCode, BigDecimal quantity,
            String uom, String notes) {
    }
}
