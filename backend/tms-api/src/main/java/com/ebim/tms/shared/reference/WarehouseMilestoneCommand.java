package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One warehouse milestone (WAREHOUSE_EXECUTION_V1 §4.2), validated for shape by the integration module. */
public record WarehouseMilestoneCommand(
        UUID integrationClientId,
        String sourceSystem,
        String eventId,
        String type,
        String transportReference,
        String loadReference,
        String warehouseCode,
        OffsetDateTime occurredAt) {
}
