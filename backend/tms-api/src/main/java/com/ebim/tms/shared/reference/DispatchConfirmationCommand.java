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

    /** One order in the document, keyed by its ERP namespace and reference. */
    public record Order(
            String externalSource,
            String externalReference,
            String warehouseOrderNumber,
            String status,
            Integer handlingUnits,
            BigDecimal weightKg,
            BigDecimal volumeM3,
            List<Line> lines) {

        public Order {
            lines = lines == null ? List.of() : List.copyOf(lines);
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
