package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleBodyType;
import com.ebim.tms.shared.imports.ImportOutcome;
import java.math.BigDecimal;

/** One vehicle type's row in an import report, as the operator reviews it. */
public record VehicleTypeImportPreview(
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

    static VehicleTypeImportPreview from(VehicleTypeImportCandidate candidate) {
        return new VehicleTypeImportPreview(candidate.code(), candidate.outcome(), candidate.rowNumber(),
                candidate.name(), candidate.maxWeightKg(), candidate.maxVolumeM3(), candidate.maxPallets(),
                candidate.lengthM(), candidate.widthM(), candidate.heightM(), candidate.bodyType(),
                candidate.temperatureControlled(), candidate.minTemperatureCelsius(),
                candidate.maxTemperatureCelsius(), candidate.axles());
    }
}
