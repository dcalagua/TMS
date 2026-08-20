package com.ebim.tms.fleet.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportLimits;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Carrier import template's own examples, put back through the parser and validator. */
class CarrierImportTemplateTest {

    private static final CarrierImportTemplate TEMPLATE = new CarrierImportTemplate();
    private static final CarrierImportParser PARSER = new CarrierImportParser();
    private static final CarrierImportValidator.MasterSnapshot EMPTY_COMPANY =
            new CarrierImportValidator.MasterSnapshot(Set.of());

    private static CarrierImportValidator.Result importTemplate(ImportFormat format) {
        byte[] content = TEMPLATE.build(format);
        return CarrierImportValidator.validate(PARSER.parse(content, format, ImportLimits.standard()), EMPTY_COMPANY);
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
    @DisplayName("both formats describe the same carriers")
    void formatsAgree() {
        assertThat(importTemplate(ImportFormat.CSV).candidates()).isEqualTo(importTemplate(ImportFormat.XLSX).candidates());
    }
}
