package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.DispatchConfirmationResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The trip workspace's "Warehouse dispatch" card (ADR-013): how the trip departed, what the
 * warehouse said, and how the two compare. Plan and reality side by side, never merged.
 *
 * @param verificationStatus derived: UNVERIFIED with no current document, MISMATCH when any current
 *     document is, MATCHED (or OVERRIDDEN) otherwise
 */
public record TripWarehouseView(
        UUID tripId,
        String shipmentNumber,
        String dispatchConfirmationMode,
        String dispatchSource,
        OffsetDateTime actualDepartureAt,
        String verificationStatus,
        List<Document> documents,
        List<Milestone> milestones) {

    public record Document(
            UUID id,
            String sourceSystem,
            String dispatchReference,
            int revision,
            boolean current,
            String outcome,
            String verificationStatus,
            String loadReference,
            String warehouseCode,
            OffsetDateTime actualDispatchAt,
            String carrierCode,
            String carrierName,
            String vehicleLicensePlate,
            String driverName,
            String driverDocumentNumber,
            String sealNumber,
            String transportDocumentNumber,
            Integer totalHandlingUnits,
            BigDecimal totalWeightKg,
            BigDecimal totalVolumeM3,
            OffsetDateTime receivedAt,
            List<DispatchConfirmationResult.Discrepancy> discrepancies,
            List<Order> orders) {
    }

    public record Order(UUID orderId, String externalSource, String externalReference, String warehouseOrderNumber,
            String status, Integer handlingUnits, BigDecimal weightKg, BigDecimal volumeM3, String matchResult) {
    }

    public record Milestone(String type, String eventId, String loadReference, String warehouseCode,
            OffsetDateTime occurredAt, OffsetDateTime receivedAt) {
    }
}
