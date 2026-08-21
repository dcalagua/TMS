package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportTemplateWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/** Generates the downloadable Vehicle import template. See {@code LocationImportTemplate}. */
@Component
public class VehicleImportTemplate {

    private static final List<List<String>> EXAMPLE_ROWS = List.of(
            List.of("VEH-001", "ABC-123", "CARR-01", "VT-DRYVAN-10T", "", "", "", "AVAILABLE", ""),
            List.of("VEH-002", "XYZ-987", "", "VT-REEFER-5T", "5200", "23", "13", "AVAILABLE", "LEGACY-VEH-002"));

    private static final List<String> INSTRUCTIONS = List.of(
            "TMS by EBIM - vehicle import",
            "",
            "1. Fill the 'Vehicles' sheet. Delete the EXAMPLE rows before uploading.",
            "2. One row is one vehicle. code and licensePlate are both required and unique per company.",
            "3. vehicleTypeCode must match an existing vehicle type in this company.",
            "4. carrierCode is optional: leave blank for an owned-fleet vehicle with no third-party carrier.",
            "5. Upload the file and review the preview. Nothing is saved until you confirm.",
            "6. If any row has an error, nothing at all is imported - fix the file and upload it again.",
            "7. Re-uploading a file is safe: vehicles whose code or license plate already exists are skipped.",
            "",
            "Columns");

    public byte[] build(ImportFormat format) {
        return ImportTemplateWriter.build(format, "Vehicles", VehicleImportColumn.SPECS, EXAMPLE_ROWS, INSTRUCTIONS);
    }

    public String fileName(ImportFormat format) {
        return ImportTemplateWriter.fileName("vehicles", format);
    }
}
