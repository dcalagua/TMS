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

class CarrierImportValidatorTest {

    private static final CarrierImportValidator.MasterSnapshot EMPTY_COMPANY =
            new CarrierImportValidator.MasterSnapshot(Set.of());

    private static ImportRow row(int rowNumber, Map<String, String> values) {
        return new ImportRow(rowNumber, values);
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CarrierImportColumn.CODE.header(), "CARR-01");
        values.put(CarrierImportColumn.BUSINESS_NAME.header(), "Acme Transport");
        values.put(CarrierImportColumn.TAX_ID_TYPE.header(), "RUC");
        values.put(CarrierImportColumn.TAX_ID_VALUE.header(), "20123456789");
        return values;
    }

    @Test
    @DisplayName("a minimal valid row becomes a creatable candidate")
    void minimalValidRow() {
        var result = CarrierImportValidator.validate(List.of(row(2, baseValues())), EMPTY_COMPANY);

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.CREATE);
    }

    @Test
    @DisplayName("a code already used by the company is skipped, not rejected")
    void existingCodeIsSkipped() {
        var snapshot = new CarrierImportValidator.MasterSnapshot(Set.of("CARR-01"));
        var result = CarrierImportValidator.validate(List.of(row(2, baseValues())), snapshot);

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.SKIPPED_DUPLICATE);
    }

    @Test
    @DisplayName("a tax id pair repeated within the file is rejected on its second occurrence")
    void duplicateTaxIdWithinFileIsRejected() {
        Map<String, String> second = baseValues();
        second.put(CarrierImportColumn.CODE.header(), "CARR-02");
        var result = CarrierImportValidator.validate(List.of(row(2, baseValues()), row(3, second)), EMPTY_COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.CREATE);
        assertThat(result.candidates().get(1).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("an email that does not look like one is rejected")
    void malformedEmailIsRejected() {
        Map<String, String> values = baseValues();
        values.put(CarrierImportColumn.EMAIL.header(), "not-an-email");
        var result = CarrierImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY);

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("missing required fields are reported per column")
    void requiredFieldsAreEnforced() {
        var result = CarrierImportValidator.validate(List.of(row(2, Map.of())), EMPTY_COMPANY);

        assertThat(result.issues()).extracting("column").containsExactlyInAnyOrder(
                CarrierImportColumn.CODE.header(), CarrierImportColumn.BUSINESS_NAME.header(),
                CarrierImportColumn.TAX_ID_TYPE.header(), CarrierImportColumn.TAX_ID_VALUE.header());
    }
}
