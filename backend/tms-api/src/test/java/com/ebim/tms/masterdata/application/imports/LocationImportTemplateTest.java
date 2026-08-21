package com.ebim.tms.masterdata.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportLimits;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The template a user downloads, put straight back through the parser and validator that will
 * read their filled-in copy - see {@code OrderImportTemplateTest} for why this is the test that
 * keeps the three in step.
 */
class LocationImportTemplateTest {

    private static final LocationImportTemplate TEMPLATE = new LocationImportTemplate();
    private static final LocationImportParser PARSER = new LocationImportParser();

    private static final LocationImportValidator.MasterSnapshot COMPANY = new LocationImportValidator.MasterSnapshot(
            Map.of("ZONE-NORTH", UUID.randomUUID()), Set.of());

    private static LocationImportValidator.Result importTemplate(ImportFormat format) {
        byte[] content = TEMPLATE.build(format);
        return LocationImportValidator.validate(PARSER.parse(content, format, ImportLimits.standard()), COMPANY,
                "America/Lima");
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
    @DisplayName("both formats describe the same locations")
    void formatsAgree() {
        assertThat(importTemplate(ImportFormat.CSV).candidates()).isEqualTo(importTemplate(ImportFormat.XLSX).candidates());
    }

    @Test
    @DisplayName("the examples cover a location with a zone and one without")
    void examplesCoverZoneAndNoZone() {
        var candidates = importTemplate(ImportFormat.XLSX).candidates();
        assertThat(candidates).extracting(LocationImportCandidate::code).containsExactly("DC-LIMA", "STORE-01");
        assertThat(candidates.get(0).zoneId()).isNull();
        assertThat(candidates.get(1).zoneId()).isEqualTo(COMPANY.zoneIdsByCode().get("ZONE-NORTH"));
    }
}
