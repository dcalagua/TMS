package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportTemplateWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/** Generates the downloadable Carrier import template. See {@code LocationImportTemplate}. */
@Component
public class CarrierImportTemplate {

    private static final List<List<String>> EXAMPLE_ROWS = List.of(
            List.of("CARR-01", "Transportes Acme S.A.C.", "RUC", "20123456789", "Maria Lopez", "+51 999 111 222",
                    "ops@acme-transportes.pe", ""),
            List.of("CARR-02", "Logistica del Sur E.I.R.L.", "RUC", "20456789123", "", "", "", "LEGACY-CARR-02"));

    private static final List<String> INSTRUCTIONS = List.of(
            "TMS by EBIM - carrier import",
            "",
            "1. Fill the 'Carriers' sheet. Delete the EXAMPLE rows before uploading.",
            "2. One row is one carrier. code is required and unique per company.",
            "3. taxIdType/taxIdValue together must be unique per company.",
            "4. Upload the file and review the preview. Nothing is saved until you confirm.",
            "5. If any row has an error, nothing at all is imported - fix the file and upload it again.",
            "6. Re-uploading a file is safe: carriers whose code already exists are skipped.",
            "",
            "Columns");

    public byte[] build(ImportFormat format) {
        return ImportTemplateWriter.build(format, "Carriers", CarrierImportColumn.SPECS, EXAMPLE_ROWS, INSTRUCTIONS);
    }

    public String fileName(ImportFormat format) {
        return ImportTemplateWriter.fileName("carriers", format);
    }
}
