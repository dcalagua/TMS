package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code WAREHOUSE_MILESTONE}, contract v1 (WAREHOUSE_EXECUTION_V1 §4.2): up to 200 milestones in
 * one delivery. Items are judged one by one, so a malformed item does not refuse its neighbours.
 */
public record WarehouseMilestoneBatchV1(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
        String sourceSystem,
        @NotEmpty(message = "must contain at least one milestone")
        @Size(max = 200, message = "must contain at most 200 milestones")
        List<@Valid @NotNull Milestone> milestones) {

    /** One milestone. Its fields are checked per item, not as a batch-level refusal. */
    public record Milestone(
            String eventId,
            String type,
            String transportReference,
            String loadReference,
            String warehouseCode,
            OffsetDateTime occurredAt) {
    }
}
