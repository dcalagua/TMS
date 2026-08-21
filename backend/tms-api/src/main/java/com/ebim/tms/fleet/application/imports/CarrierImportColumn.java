package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportColumnSpec;
import java.util.Arrays;
import java.util.List;

/**
 * The columns of the bulk Carrier import file, in the order the template writes them. See
 * {@code com.ebim.tms.masterdata.application.imports.LocationImportColumn} for the pattern this
 * follows: one row is one carrier, identified by {@link #CODE}.
 */
public enum CarrierImportColumn {

    CODE("code", true, "Required. Unique per company. Re-importing a code that already exists is skipped, not duplicated."),
    BUSINESS_NAME("businessName", true, "Required."),
    TAX_ID_TYPE("taxIdType", true, "Required. Free text, e.g. RUC, RFC, CNPJ, EIN - varies by country."),
    TAX_ID_VALUE("taxIdValue", true, "Required. Unique per company together with taxIdType."),
    CONTACT_NAME("contactName", false, "Optional."),
    PHONE("phone", false, "Optional."),
    EMAIL("email", false, "Optional. Must look like an email address."),
    EXTERNAL_REFERENCE("externalReference", false, "Optional. A pointer back to this carrier in an external fleet/ERP system.");

    private final String header;
    private final boolean required;
    private final String helpText;

    CarrierImportColumn(String header, boolean required, String helpText) {
        this.header = header;
        this.required = required;
        this.helpText = helpText;
    }

    public String header() {
        return header;
    }

    public boolean required() {
        return required;
    }

    public ImportColumnSpec spec() {
        return new ImportColumnSpec(header, required, helpText);
    }

    public static final List<ImportColumnSpec> SPECS =
            List.of(Arrays.stream(values()).map(CarrierImportColumn::spec).toArray(ImportColumnSpec[]::new));
}
