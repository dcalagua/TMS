package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import com.ebim.tms.shared.imports.ImportOutcome;
import java.math.BigDecimal;

/** One vehicle's row in an import report, as the operator reviews it. */
public record VehicleImportPreview(
        String code,
        ImportOutcome outcome,
        int rowNumber,
        String licensePlate,
        String carrierCode,
        String vehicleTypeCode,
        BigDecimal maxWeightOverrideKg,
        BigDecimal maxVolumeOverrideM3,
        Integer maxPalletsOverride,
        VehicleAvailabilityStatus availabilityStatus,
        String externalReference) {

    static VehicleImportPreview from(VehicleImportCandidate candidate) {
        return new VehicleImportPreview(candidate.code(), candidate.outcome(), candidate.rowNumber(),
                candidate.licensePlate(), candidate.carrierCode(), candidate.vehicleTypeCode(),
                candidate.maxWeightOverrideKg(), candidate.maxVolumeOverrideM3(), candidate.maxPalletsOverride(),
                candidate.availabilityStatus(), candidate.externalReference());
    }
}
