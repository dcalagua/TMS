package com.ebim.tms.costing.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Configuring what one of our trucks costs to run (V48, JOB 22).
 *
 * <p><b>Every rate is optional and null means "we do not charge for this".</b> The form must send
 * null rather than 0 for a component the company does not model, because zero is a different
 * statement - it charges nothing and still demands its quantity before the estimate is comparable.
 * The screen says so beside the fields.
 */
public record OwnFleetCostProfileRequest(
        UUID vehicleId,
        UUID vehicleTypeId,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO-4217 code")
        String currency,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal fixedTripAmount,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal fuelPerKm,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal driverPerHour,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal vehiclePerHour,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal maintenancePerKm,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal depreciationPerKm,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal tollAmount,
        @Size(max = 2000) String notes) {
}
