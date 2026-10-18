package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code WAREHOUSE_ORDER_EVENT}, contract v1.1 (WAREHOUSE_EXECUTION_V1 7.5): up to 200 events in one
 * delivery. Items are judged one by one, so a malformed item does not refuse its neighbours.
 */
public record WarehouseOrderEventBatchV1(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
        String sourceSystem,
        @NotEmpty(message = "must contain at least one event")
        @Size(max = 200, message = "must contain at most 200 events")
        List<@Valid @NotNull Event> events) {

    /**
     * One event. Its fields are checked per item, not as a batch-level refusal.
     *
     * @param type {@code ORDER_CANCELLED} or {@code ORDER_SHORT_CLOSED}
     * @param lines optional; for {@code ORDER_SHORT_CLOSED}, the quantities per ERP line
     */
    public record Event(
            String eventId,
            String type,
            String externalSource,
            String externalReference,
            String warehouseOrderNumber,
            String warehouseCode,
            OffsetDateTime occurredAt,
            String reason,
            List<Line> lines) {
    }

    /** What the warehouse was asked for, shipped and cancelled on one ERP line, in {@code uom}. */
    public record Line(
            Integer lineNumber,
            String materialCode,
            String uom,
            BigDecimal requested,
            BigDecimal shipped,
            BigDecimal cancelled) {
    }
}
