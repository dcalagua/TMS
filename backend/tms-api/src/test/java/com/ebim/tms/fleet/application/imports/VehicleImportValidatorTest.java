package com.ebim.tms.fleet.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VehicleImportValidatorTest {

    private static final UUID VEHICLE_TYPE_ID = UUID.randomUUID();

    private static final VehicleImportValidator.MasterSnapshot COMPANY = new VehicleImportValidator.MasterSnapshot(
            Map.of(), Map.of("VT-01", VEHICLE_TYPE_ID), Set.of(), Set.of());

    private static ImportRow row(int rowNumber, Map<String, String> values) {
        return new ImportRow(rowNumber, values);
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(VehicleImportColumn.CODE.header(), "VEH-01");
        values.put(VehicleImportColumn.LICENSE_PLATE.header(), "ABC-123");
        values.put(VehicleImportColumn.VEHICLE_TYPE_CODE.header(), "VT-01");
        return values;
    }

    @Test
    @DisplayName("a minimal valid row resolves its vehicle type and has no carrier")
    void minimalValidRow() {
        var result = VehicleImportValidator.validate(List.of(row(2, baseValues())), COMPANY);

        assertThat(result.issues()).isEmpty();
        VehicleImportCandidate candidate = result.candidates().get(0);
        assertThat(candidate.outcome()).isEqualTo(ImportOutcome.CREATE);
        assertThat(candidate.vehicleTypeId()).isEqualTo(VEHICLE_TYPE_ID);
        assertThat(candidate.carrierId()).isNull();
    }

    @Test
    @DisplayName("a vehicle type code that does not resolve in the company is an issue")
    void unresolvedVehicleTypeCodeIsRejected() {
        Map<String, String> values = baseValues();
        values.put(VehicleImportColumn.VEHICLE_TYPE_CODE.header(), "NO-SUCH-TYPE");
        var result = VehicleImportValidator.validate(List.of(row(2, values)), COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("an existing code OR an existing license plate is enough to skip the row")
    void existingCodeOrPlateIsSkipped() {
        var byCode = new VehicleImportValidator.MasterSnapshot(
                Map.of(), Map.of("VT-01", VEHICLE_TYPE_ID), Set.of("VEH-01"), Set.of());
        assertThat(VehicleImportValidator.validate(List.of(row(2, baseValues())), byCode).candidates().get(0).outcome())
                .isEqualTo(ImportOutcome.SKIPPED_DUPLICATE);

        var byPlate = new VehicleImportValidator.MasterSnapshot(
                Map.of(), Map.of("VT-01", VEHICLE_TYPE_ID), Set.of(), Set.of("ABC-123"));
        assertThat(VehicleImportValidator.validate(List.of(row(2, baseValues())), byPlate).candidates().get(0).outcome())
                .isEqualTo(ImportOutcome.SKIPPED_DUPLICATE);
    }

    @Test
    @DisplayName("a license plate repeated within the file is rejected on its second occurrence")
    void duplicatePlateWithinFileIsRejected() {
        Map<String, String> second = baseValues();
        second.put(VehicleImportColumn.CODE.header(), "VEH-02");
        var result = VehicleImportValidator.validate(List.of(row(2, baseValues()), row(3, second)), COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.CREATE);
        assertThat(result.candidates().get(1).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("availabilityStatus defaults to AVAILABLE when left blank")
    void availabilityStatusDefaultsToAvailable() {
        var result = VehicleImportValidator.validate(List.of(row(2, baseValues())), COMPANY);
        assertThat(result.candidates().get(0).availabilityStatus().name()).isEqualTo("AVAILABLE");
    }
}
