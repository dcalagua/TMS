package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleBodyType;
import com.ebim.tms.shared.imports.ImportOutcome;
import java.math.BigDecimal;

/** One vehicle type as a validated file describes it. Mirrors {@code LocationImportCandidate}. */
public record VehicleTypeImportCandidate(
        String code,
        ImportOutcome outcome,
        int rowNumber,
        String name,
        BigDecimal maxWeightKg,
        BigDecimal maxVolumeM3,
        int maxPallets,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        VehicleBodyType bodyType,
        boolean temperatureControlled,
        BigDecimal minTemperatureCelsius,
        BigDecimal maxTemperatureCelsius,
        Integer axles) {

    public boolean isCreatable() {
        return outcome == ImportOutcome.CREATE;
    }
}
