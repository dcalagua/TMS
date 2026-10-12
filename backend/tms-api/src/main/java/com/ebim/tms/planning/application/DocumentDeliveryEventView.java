package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDeliveryEventType;
import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One entry of a tracked document's history. */
public record DocumentDeliveryEventView(UUID id, DocumentDeliveryEventType eventType, DocumentDeliveryStatus status,
        DocumentDeliveryReason reasonCode, String remark, OffsetDateTime reportedAt, String actorName,
        OffsetDateTime recordedAt) {
}
