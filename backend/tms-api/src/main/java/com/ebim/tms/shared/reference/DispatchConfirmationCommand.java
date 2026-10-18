package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A warehouse system's dispatch document as TMS received it (WAREHOUSE_EXECUTION_V1 §4.1), already
 * validated for shape by the integration module. Carries the raw body and its hash so the planning
 * module can store the document exactly as it arrived.
 *
 * @param sourceSystem the producer of the dispatch (EWM by EBIM: {@code EWM_EBIM}), never an ERP
 *     namespace
 * @param vehicleTrailerPlate contract v1.1 (§7.3): the trailer, or null. Stored, never compared
 */
public record DispatchConfirmationCommand(
        UUID integrationClientId,
        String idempotencyKey,
        String correlationId,
        String sourceSystem,
        String dispatchReference,
        int revision,
        String transportReference,
        String loadReference,
        String warehouseCode,
        OffsetDateTime actualDispatchAt,
        String carrierCode,
        String carrierName,
        String vehicleLicensePlate,
        String vehicleType,
        String vehicleTrailerPlate,
        String driverName,
        String driverDocumentNumber,
        String sealNumber,
        String transportDocumentNumber,
        Integer totalHandlingUnits,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        List<Order> orders,
        String rawBody,
        String payloadHash) {

    public DispatchConfirmationCommand {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    /**
     * One order in the document, keyed by its ERP namespace and reference.
     *
     * <p>{@code stacks}, {@code effectiveVolumeM3}, {@code loadingMeters} and {@code geometryReason}
     * are contract v1.1 (§7.3): the geometry the warehouse computed (ADR-022 section 3), each null
     * when not said. Stored, and compared with the plan as an informative {@code GEOMETRY_VARIANCE}
     * (phase 45, {@code DispatchReconciler}).
     */
    public record Order(
            String externalSource,
            String externalReference,
            String warehouseOrderNumber,
            String status,
            Integer handlingUnits,
            BigDecimal weightKg,
            BigDecimal volumeM3,
            List<Line> lines,
            Integer stacks,
            BigDecimal effectiveVolumeM3,
            BigDecimal loadingMeters,
            String geometryReason) {

        public Order {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** The v1 shape, with no geometry. */
        public Order(String externalSource, String externalReference, String warehouseOrderNumber, String status,
                Integer handlingUnits, BigDecimal weightKg, BigDecimal volumeM3, List<Line> lines) {
            this(externalSource, externalReference, warehouseOrderNumber, status, handlingUnits, weightKg, volumeM3,
                    lines, null, null, null, null);
        }

        public OrderExternalKey key() {
            return new OrderExternalKey(externalSource, externalReference);
        }
    }

    /** One dispatched line; several may carry the same line number (one per handling unit). */
    public record Line(Integer lineNumber, String materialCode, String lotCode, BigDecimal quantity, String uom,
            String handlingUnitCode) {
    }
}
