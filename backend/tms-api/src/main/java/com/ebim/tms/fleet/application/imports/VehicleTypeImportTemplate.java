package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportTemplateWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/** Generates the downloadable Vehicle Type import template. See {@code LocationImportTemplate}. */
@Component
public class VehicleTypeImportTemplate {

    private static final List<List<String>> EXAMPLE_ROWS = List.of(
            List.of("VT-DRYVAN-10T", "10T Dry Van", "10000", "45", "20", "9.5", "2.4", "2.6", "DRY_VAN", "FALSE",
                    "", "", "3"),
            List.of("VT-REEFER-5T", "5T Refrigerated", "5000", "22", "12", "6.2", "2.3", "2.5", "REFRIGERATED",
                    "TRUE", "-18", "4", "2"));

    private static final List<String> INSTRUCTIONS = List.of(
            "TMS by EBIM - vehicle type import",
            "",
            "1. Fill the 'Vehicle Types' sheet. Delete the EXAMPLE rows before uploading.",
            "2. One row is one vehicle type. code is required and unique per company.",
            "3. Units are explicit: kilograms, cubic meters, meters, Celsius. Never mix kg/tons or m3/cm3.",
            "4. Upload the file and review the preview. Nothing is saved until you confirm.",
            "5. If any row has an error, nothing at all is imported - fix the file and upload it again.",
            "6. Re-uploading a file is safe: vehicle types whose code already exists are skipped.",
            "",
            "Columns");

    public byte[] build(ImportFormat format) {
        return ImportTemplateWriter.build(
                format, "Vehicle Types", VehicleTypeImportColumn.SPECS, EXAMPLE_ROWS, INSTRUCTIONS);
    }

    public String fileName(ImportFormat format) {
        return ImportTemplateWriter.fileName("vehicle-types", format);
    }
}
