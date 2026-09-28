package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code DISPATCH_CONFIRMED}, contract v1 (WAREHOUSE_EXECUTION_V1 §4.1): what a warehouse system
 * says physically left. Unknown fields are ignored; additive fields do not change the version.
 *
 * @param sourceSystem the PRODUCER of the dispatch ({@code EWM_EBIM}), not an ERP namespace
 */
public record DispatchConfirmationV1(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
        String sourceSystem,
        @NotBlank(message = "is required") @Size(max = 80, message = "must be at most 80 characters")
        String dispatchReference,
        @NotNull(message = "is required") @Min(value = 1, message = "must be at least 1")
        Integer revision,
        @NotBlank(message = "is required") @Size(max = 80, message = "must be at most 80 characters")
        String transportReference,
        @Size(max = 80, message = "must be at most 80 characters") String loadReference,
        @Size(max = 80, message = "must be at most 80 characters") String warehouseCode,
        @NotNull(message = "is required") OffsetDateTime actualDispatchAt,
        @Valid Party carrier,
        @Valid Vehicle vehicle,
        @Valid Driver driver,
        @Size(max = 80, message = "must be at most 80 characters") String sealNumber,
        @Size(max = 80, message = "must be at most 80 characters") String transportDocumentNumber,
        @Valid Totals totals,
        @Size(max = 2000, message = "must have at most 2000 orders") List<@Valid @NotNull Order> orders,
        // Optional, stored as received inside the raw body; carried here only so it is validated.
        @Valid Document document) {

    public record Party(
            @Size(max = 80, message = "must be at most 80 characters") String code,
            @Size(max = 200, message = "must be at most 200 characters") String name) {
    }

    public record Vehicle(
            @Size(max = 20, message = "must be at most 20 characters") String licensePlate,
            @Size(max = 80, message = "must be at most 80 characters") String type) {
    }

    public record Driver(
            @Size(max = 200, message = "must be at most 200 characters") String name,
            @Size(max = 40, message = "must be at most 40 characters") String documentNumber) {
    }

    public record Totals(
            @PositiveOrZero(message = "cannot be negative") Integer handlingUnits,
            @PositiveOrZero(message = "cannot be negative") BigDecimal weightKg,
            @PositiveOrZero(message = "cannot be negative") BigDecimal volumeM3) {
    }

    /** An order, keyed by its ERP namespace and reference - byte for byte, never normalised. */
    public record Order(
            @NotBlank(message = "is required")
            @Pattern(regexp = "^[A-Z0-9_]{2,40}$", message = "must be 2-40 upper-case letters, digits or '_'")
            String externalSource,
            @NotBlank(message = "is required") @Size(max = 120, message = "must be at most 120 characters")
            String externalReference,
            @Size(max = 80, message = "must be at most 80 characters") String warehouseOrderNumber,
            @Size(max = 40, message = "must be at most 40 characters") String status,
            @PositiveOrZero(message = "cannot be negative") Integer handlingUnits,
            @PositiveOrZero(message = "cannot be negative") BigDecimal weightKg,
            @PositiveOrZero(message = "cannot be negative") BigDecimal volumeM3,
            @Size(max = 5000, message = "must have at most 5000 lines") List<@Valid @NotNull Line> lines) {
    }

    public record Line(
            @Min(value = 1, message = "must be at least 1") Integer lineNumber,
            @Size(max = 80, message = "must be at most 80 characters") String materialCode,
            @Size(max = 80, message = "must be at most 80 characters") String lotCode,
            @PositiveOrZero(message = "cannot be negative") BigDecimal quantity,
            @Size(max = 20, message = "must be at most 20 characters") String uom,
            @Size(max = 80, message = "must be at most 80 characters") String handlingUnitCode) {
    }

    public record Document(
            @Size(max = 40, message = "must be at most 40 characters") String format,
            @Size(max = 40, message = "must be at most 40 characters") String version,
            @Size(max = 100, message = "must be at most 100 characters") String contentType,
            String content) {
    }
}
