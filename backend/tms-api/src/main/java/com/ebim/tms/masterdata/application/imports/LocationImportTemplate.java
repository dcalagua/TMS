package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportTemplateWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Generates the downloadable Location import template, in both formats the import accepts, from
 * {@link LocationImportColumn} rather than a file kept in {@code resources} - see
 * {@code OrderImportTemplate} for why a generated template cannot drift from its parser.
 */
@Component
public class LocationImportTemplate {

    private static final List<List<String>> EXAMPLE_ROWS = List.of(
            List.of("DC-LIMA", "Lima Distribution Center", "DISTRIBUTION_CENTER", "ORIGIN", "Av. Argentina 1234",
                    "Blue gate next to the pharmacy", "Callao", "Callao", "Callao", "PE", "America/Lima",
                    "-12.0464", "-77.0428", "", "15", "", ""),
            List.of("STORE-01", "Acme Store 01", "STORE", "DESTINATION", "Jr. de la Union 500", "", "Lima", "Lima",
                    "Lima", "PE", "America/Lima", "", "", "ZONE-NORTH", "10", "LEGACY", "ST-001"));

    private static final List<String> INSTRUCTIONS = List.of(
            "TMS by EBIM - location import",
            "",
            "1. Fill the 'Locations' sheet. Delete the EXAMPLE rows before uploading.",
            "2. One row is one location. code is required and unique per company.",
            "3. roles is the operational use, not the type: ORIGIN, DESTINATION, or ORIGIN,DESTINATION.",
            "4. Upload the file and review the preview. Nothing is saved until you confirm.",
            "5. If any row has an error, nothing at all is imported - fix the file and upload it again.",
            "6. Re-uploading a file is safe: locations whose code already exists are skipped.",
            "",
            "Columns");

    public byte[] build(ImportFormat format) {
        return ImportTemplateWriter.build(format, "Locations", LocationImportColumn.SPECS, EXAMPLE_ROWS, INSTRUCTIONS);
    }

    public String fileName(ImportFormat format) {
        return ImportTemplateWriter.fileName("locations", format);
    }
}
