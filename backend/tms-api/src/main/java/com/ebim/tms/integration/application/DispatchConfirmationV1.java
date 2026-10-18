package com.ebim.tms.integration.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
 * <p>The v1.1 fields (§7.3: the trailer plate and the per-order geometry) are optional and marked
 * {@code NON_NULL}: {@link PayloadHash} fingerprints this record's re-serialisation, so a v1 body
 * must serialise exactly as it did before they existed, or a redelivery of a document stored
 * earlier would be refused as different content.
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

    /**
     * @param licensePlate the tractor / truck plate - the only one {@code VEHICLE_MISMATCH} compares
     * @param trailerLicensePlate v1.1: the trailer, when there is one. Stored and shown, never
     *     compared: TMS has no trailer master
     */
    public record Vehicle(
            @Size(max = 20, message = "must be at most 20 characters") String licensePlate,
            @Size(max = 80, message = "must be at most 80 characters") String type,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Size(max = 20, message = "must be at most 20 characters") String trailerLicensePlate) {
    }

    public record Driver(
            @Size(max = 200, message = "must be at most 200 characters") String name,
            @Size(max = 40, message = "must be at most 40 characters") String documentNumber) {
    }

    public record Totals(
            @PositiveOrZero(message = "cannot be negative") Integer handlingUnits,
            @PositiveOrZero(message = "cannot be negative")
            @DecimalMax(value = MAX_WEIGHT_KG, message = "must be at most " + MAX_WEIGHT_KG) BigDecimal weightKg,
            @PositiveOrZero(message = "cannot be negative")
            @DecimalMax(value = MAX_VOLUME_M3, message = "must be at most " + MAX_VOLUME_M3) BigDecimal volumeM3) {
    }

    // The columns' own bounds (V53 numeric(14,3) and (14,4), V60 numeric(10,3)): a figure beyond them
    // is refused here with a 400 naming the field, instead of reaching the database and coming back
    // as a "retry the delivery" 409 that no retry can ever clear.
    static final String MAX_WEIGHT_KG = "99999999999.999";
    static final String MAX_VOLUME_M3 = "9999999999.9999";
    static final String MAX_LOADING_METERS = "9999999.999";

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
            @PositiveOrZero(message = "cannot be negative")
            @DecimalMax(value = MAX_WEIGHT_KG, message = "must be at most " + MAX_WEIGHT_KG) BigDecimal weightKg,
            @PositiveOrZero(message = "cannot be negative")
            @DecimalMax(value = MAX_VOLUME_M3, message = "must be at most " + MAX_VOLUME_M3) BigDecimal volumeM3,
            @Size(max = 5000, message = "must have at most 5000 lines") List<@Valid @NotNull Line> lines,
            // v1.1 (§7.3): the geometry the warehouse computed with ADR-022's footprint rules. Null
            // when it could not, with geometryReason saying why. Stored, not compared (phase 45).
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @PositiveOrZero(message = "cannot be negative") Integer stacks,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @PositiveOrZero(message = "cannot be negative")
            @DecimalMax(value = MAX_VOLUME_M3, message = "must be at most " + MAX_VOLUME_M3) BigDecimal effectiveVolumeM3,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @PositiveOrZero(message = "cannot be negative")
            @DecimalMax(value = MAX_LOADING_METERS, message = "must be at most " + MAX_LOADING_METERS)
            BigDecimal loadingMeters,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Size(max = 60, message = "must be at most 60 characters") String geometryReason) {
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
