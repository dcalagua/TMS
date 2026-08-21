package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Create and update share one shape; see {@code OriginRequest} (masterdata module) for the
 * reasoning behind the lower-case-accepting code pattern and the company/id coming from
 * context rather than the body.
 *
 * <p>{@code carrierId} is optional (a company may run its own owned fleet with no third-party
 * carrier); {@code vehicleTypeId} is mandatory ({@code EffectiveCapacityResolver} always needs a
 * type to fall back to). The three {@code max*Override} fields are independently optional.
 */
public record VehicleRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 12) @Pattern(regexp = "^[A-Za-z0-9-]{4,12}$",
                message = "must be 4-12 characters: letters, digits or hyphen") String licensePlate,
        UUID carrierId,
        @NotNull UUID vehicleTypeId,
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal maxWeightOverrideKg,
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal maxVolumeOverrideM3,
        @Min(value = 0, message = "must be zero or greater") Integer maxPalletsOverride,
        @NotNull VehicleAvailabilityStatus availabilityStatus,
        @Size(max = 200) String externalReference) {
}
