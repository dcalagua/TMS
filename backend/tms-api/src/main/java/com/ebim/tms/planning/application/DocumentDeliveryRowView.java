package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.planning.domain.TripStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One tracked document in the office monitor (ADR-018).
 *
 * @param inconsistency {@code DOCUMENT_DELIVERED_GOODS_FAILED} or {@code GOODS_DELIVERED_DOCUMENT_NOT}
 *     when the document state disagrees with the goods' delivery result on this trip (ADR-009);
 *     null otherwise. Shown, never resolved
 */
public record DocumentDeliveryRowView(
        UUID id,
        UUID tripId,
        String shipmentNumber,
        TripStatus tripStatus,
        OffsetDateTime dispatchedAt,
        UUID carrierId,
        String carrierName,
        String vehicleLicensePlate,
        String driverName,
        UUID documentId,
        String documentType,
        String documentNumber,
        List<String> orderNumbers,
        String customerName,
        DocumentDeliveryStatus status,
        DocumentDeliveryReason reasonCode,
        String remark,
        OffsetDateTime reportedAt,
        OffsetDateTime recordedAt,
        String recordedByName,
        OffsetDateTime returnedAt,
        String returnedByName,
        int evidenceCount,
        String inconsistency) {
}
