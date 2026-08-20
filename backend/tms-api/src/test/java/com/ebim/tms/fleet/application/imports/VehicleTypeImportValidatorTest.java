package com.ebim.tms.fleet.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VehicleTypeImportValidatorTest {

    private static final VehicleTypeImportValidator.MasterSnapshot EMPTY_COMPANY =
            new VehicleTypeImportValidator.MasterSnapshot(Set.of());

    private static ImportRow row(int rowNumber, Map<String, String> values) {
        return new ImportRow(rowNumber, values);
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(VehicleTypeImportColumn.CODE.header(), "VT-01");
        values.put(VehicleTypeImportColumn.NAME.header(), "10T Dry Van");
        values.put(VehicleTypeImportColumn.MAX_WEIGHT_KG.header(), "10000");
        values.put(VehicleTypeImportColumn.MAX_VOLUME_M3.header(), "45");
        values.put(VehicleTypeImportColumn.MAX_PALLETS.header(), "20");
        return values;
    }

    @Test
    @DisplayName("a minimal valid row becomes a creatable candidate, not temperature controlled by default")
    void minimalValidRow() {
        var result = VehicleTypeImportValidator.validate(List.of(row(2, baseValues())), EMPTY_COMPANY);

        assertThat(result.issues()).isEmpty();
        VehicleTypeImportCandidate candidate = result.candidates().get(0);
        assertThat(candidate.outcome()).isEqualTo(ImportOutcome.CREATE);
        assertThat(candidate.temperatureControlled()).isFalse();
    }

    @Test
    @DisplayName("a code already used by the company is skipped, not rejected")
    void existingCodeIsSkipped() {
        var snapshot = new VehicleTypeImportValidator.MasterSnapshot(Set.of("VT-01"));
        var result = VehicleTypeImportValidator.validate(List.of(row(2, baseValues())), snapshot);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.SKIPPED_DUPLICATE);
    }

    @Test
    @DisplayName("a temperature range without temperatureControlled=TRUE is rejected")
    void temperatureRangeRequiresControlledFlag() {
        Map<String, String> values = baseValues();
        values.put(VehicleTypeImportColumn.MIN_TEMPERATURE_CELSIUS.header(), "-18");
        var result = VehicleTypeImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("min temperature greater than max temperature is rejected")
    void temperatureRangeOrderIsEnforced() {
        Map<String, String> values = baseValues();
        values.put(VehicleTypeImportColumn.TEMPERATURE_CONTROLLED.header(), "TRUE");
        values.put(VehicleTypeImportColumn.MIN_TEMPERATURE_CELSIUS.header(), "5");
        values.put(VehicleTypeImportColumn.MAX_TEMPERATURE_CELSIUS.header(), "-5");
        var result = VehicleTypeImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("zero or negative capacities are rejected")
    void capacitiesMustBePositive() {
        Map<String, String> values = baseValues();
        values.put(VehicleTypeImportColumn.MAX_WEIGHT_KG.header(), "0");
        var result = VehicleTypeImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }
}
