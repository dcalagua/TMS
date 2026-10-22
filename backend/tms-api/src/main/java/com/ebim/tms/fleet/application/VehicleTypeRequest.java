package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.VehicleBodyType;
import com.ebim.tms.shared.reference.FuelType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Create and update share one shape; see {@code OriginRequest} (masterdata module) for the
 * reasoning behind the lower-case-accepting code pattern and the company/id coming from
 * context rather than the body.
 *
 * <p>Units are explicit in every field name and never mixed - kilograms, cubic meters, meters,
 * Celsius. {@code maxWeightKg}/{@code maxVolumeM3} must be strictly positive (a capacity of
 * zero is meaningless); {@code maxPallets} may legitimately be zero (a tanker carries none).
 */
public record VehicleTypeRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal maxWeightKg,
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal maxVolumeM3,
        @NotNull @Min(value = 0, message = "must be zero or greater") Integer maxPallets,
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal lengthM,
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal widthM,
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal heightM,
        VehicleBodyType bodyType,
        boolean temperatureControlled,
        BigDecimal minTemperatureCelsius,
        BigDecimal maxTemperatureCelsius,
        @Min(value = 1, message = "must be at least 1") Integer axles,
        /**
         * Share of {@code length x width} a load can cover (V62, ADR-022 section 5); null = the
         * company's default, else the deployment's.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @DecimalMax(value = "1.0", message = "must be at most 1") BigDecimal loadFloorFillFactor,
        /** What the type runs on (V73); optional and descriptive. */
        FuelType fuelType,
        /**
         * kg CO2e per km driven (V73); null = no factor, so its trips show "no data" rather than zero.
         * Bounded like {@code ck_vehicle_type_emission_factor}: above 50 is a unit mistake.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @DecimalMax(value = "50.0", message = "must be at most 50") BigDecimal emissionFactorKgCo2ePerKm) {

    /** The pre-V73 shape: no fuel type and no emission factor. */
    public VehicleTypeRequest(String code, String name, BigDecimal maxWeightKg, BigDecimal maxVolumeM3,
            Integer maxPallets, BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM, VehicleBodyType bodyType,
            boolean temperatureControlled, BigDecimal minTemperatureCelsius, BigDecimal maxTemperatureCelsius,
            Integer axles, BigDecimal loadFloorFillFactor) {
        this(code, name, maxWeightKg, maxVolumeM3, maxPallets, lengthM, widthM, heightM, bodyType,
                temperatureControlled, minTemperatureCelsius, maxTemperatureCelsius, axles, loadFloorFillFactor,
                null, null);
    }
}
