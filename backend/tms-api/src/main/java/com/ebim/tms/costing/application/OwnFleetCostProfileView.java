package com.ebim.tms.costing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A configured profile as the API returns it (V48, JOB 22).
 *
 * @param state what the screen shows as a chip - see {@link OwnFleetProfileState}
 */
public record OwnFleetCostProfileView(
        UUID id,
        UUID vehicleId,
        String vehicleLabel,
        UUID vehicleTypeId,
        String vehicleTypeLabel,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean active,
        OwnFleetProfileState state,
        BigDecimal fixedTripAmount,
        BigDecimal fuelPerKm,
        BigDecimal driverPerHour,
        BigDecimal vehiclePerHour,
        BigDecimal maintenancePerKm,
        BigDecimal depreciationPerKm,
        BigDecimal tollAmount,
        boolean needsDistance,
        boolean needsDuty,
        String notes) {
}
