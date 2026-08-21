package com.ebim.tms.fleet.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportLimits;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Vehicle Type import template's own examples, put back through the parser and validator. */
class VehicleTypeImportTemplateTest {

    private static final VehicleTypeImportTemplate TEMPLATE = new VehicleTypeImportTemplate();
    private static final VehicleTypeImportParser PARSER = new VehicleTypeImportParser();
    private static final VehicleTypeImportValidator.MasterSnapshot EMPTY_COMPANY =
            new VehicleTypeImportValidator.MasterSnapshot(Set.of());

    private static VehicleTypeImportValidator.Result importTemplate(ImportFormat format) {
        byte[] content = TEMPLATE.build(format);
        return VehicleTypeImportValidator.validate(
                PARSER.parse(content, format, ImportLimits.standard()), EMPTY_COMPANY);
    }

    @Test
    @DisplayName("the .xlsx template's own example rows import without a single issue")
    void xlsxExamplesAreValid() {
        assertThat(importTemplate(ImportFormat.XLSX).issues()).isEmpty();
    }

    @Test
    @DisplayName("the .csv template's own example rows import without a single issue")
    void csvExamplesAreValid() {
        assertThat(importTemplate(ImportFormat.CSV).issues()).isEmpty();
    }

    @Test
    @DisplayName("both formats describe the same vehicle types, including the temperature-controlled example")
    void formatsAgree() {
        assertThat(importTemplate(ImportFormat.CSV).candidates()).isEqualTo(importTemplate(ImportFormat.XLSX).candidates());
        var candidates = importTemplate(ImportFormat.XLSX).candidates();
        assertThat(candidates).filteredOn(VehicleTypeImportCandidate::temperatureControlled).hasSize(1);
    }
}
