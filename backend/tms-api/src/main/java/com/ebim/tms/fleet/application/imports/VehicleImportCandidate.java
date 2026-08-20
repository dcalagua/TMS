package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import com.ebim.tms.shared.imports.ImportOutcome;
import java.math.BigDecimal;
import java.util.UUID;

/** One vehicle as a validated file describes it. Mirrors {@code LocationImportCandidate}. */
public record VehicleImportCandidate(
        String code,
        ImportOutcome outcome,
        int rowNumber,
        String licensePlate,
        String carrierCode,
        /** {@code null} when the row named no carrier - an owned-fleet vehicle. */
        UUID carrierId,
        String vehicleTypeCode,
        UUID vehicleTypeId,
        BigDecimal maxWeightOverrideKg,
        BigDecimal maxVolumeOverrideM3,
        Integer maxPalletsOverride,
        VehicleAvailabilityStatus availabilityStatus,
        String externalReference) {

    public boolean isCreatable() {
        return outcome == ImportOutcome.CREATE;
    }
}
