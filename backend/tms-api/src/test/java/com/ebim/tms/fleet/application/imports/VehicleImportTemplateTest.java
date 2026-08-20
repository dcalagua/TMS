package com.ebim.tms.fleet.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportLimits;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Vehicle import template's own examples, put back through the parser and validator. */
class VehicleImportTemplateTest {

    private static final VehicleImportTemplate TEMPLATE = new VehicleImportTemplate();
    private static final VehicleImportParser PARSER = new VehicleImportParser();

    private static final VehicleImportValidator.MasterSnapshot COMPANY = new VehicleImportValidator.MasterSnapshot(
            Map.of("CARR-01", UUID.randomUUID()),
            Map.of("VT-DRYVAN-10T", UUID.randomUUID(), "VT-REEFER-5T", UUID.randomUUID()), Set.of(), Set.of());

    private static VehicleImportValidator.Result importTemplate(ImportFormat format) {
        byte[] content = TEMPLATE.build(format);
        return VehicleImportValidator.validate(PARSER.parse(content, format, ImportLimits.standard()), COMPANY);
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
    @DisplayName("both formats agree, and the examples cover a carrier-operated and an owned-fleet vehicle")
    void formatsAgreeAndCoverBothCarrierCases() {
        assertThat(importTemplate(ImportFormat.CSV).candidates()).isEqualTo(importTemplate(ImportFormat.XLSX).candidates());
        var candidates = importTemplate(ImportFormat.XLSX).candidates();
        assertThat(candidates.get(0).carrierId()).isNotNull();
        assertThat(candidates.get(1).carrierId()).isNull();
    }
}
