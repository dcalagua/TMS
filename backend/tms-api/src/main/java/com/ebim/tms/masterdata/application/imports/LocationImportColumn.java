package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.shared.imports.ImportColumnSpec;
import java.util.Arrays;
import java.util.List;

/**
 * The columns of the bulk Location import file, in the order the template writes them. One enum
 * drives three things that must never drift apart: the generated template's header row, the
 * header matching of an uploaded file, and the per-column help text an operator reads - the same
 * role {@code OrderImportColumn} plays for the order import.
 *
 * <p>Unlike the order import, one row is one location: there is no line-grouping here, so the
 * file's identity column is simply {@link #CODE}.
 */
public enum LocationImportColumn {

    /** Required, unique per company. The idempotency key: re-importing an existing code is skipped, not duplicated. */
    CODE("code", true, "Required. Unique per company. Re-importing a code that already exists is skipped, not duplicated."),

    NAME("name", true, "Required."),

    /** One of {@code LocationType}. See the Instructions sheet for the full list. */
    TYPE("type", true,
            "Required. One of WAREHOUSE, DISTRIBUTION_CENTER, PLANT, HUB, OTHER, CUSTOMER, STORE, BRANCH, DELIVERY_POINT."),

    /**
     * How the place may be used, one or more of {@code LocationRole}, separated by commas or
     * semicolons. Not what the place is - that is {@link #TYPE}.
     */
    ROLES("roles", true,
            "Required. ORIGIN, DESTINATION, or both separated by a comma."),

    ADDRESS("address", false, "Optional."),
    ADDRESS_REFERENCE("addressReference", false, "Optional. A landmark or access note."),
    DISTRICT("district", false, "Optional."),
    PROVINCE("province", false, "Optional."),
    DEPARTMENT("department", false, "Optional."),

    /** Blank defaults to {@code PE}, the same default {@code tms.location.country} has in the database. */
    COUNTRY("country", false, "Optional. Defaults to PE if left blank."),

    /** Blank defaults to the caller's company time zone. */
    TIME_ZONE("timeZone", false, "Optional IANA time zone, e.g. America/Lima. Defaults to the company's time zone."),

    LATITUDE("latitude", false, "Optional. Needs longitude too. Between -90 and 90."),
    LONGITUDE("longitude", false, "Optional. Needs latitude too. Between -180 and 180."),

    /** Code of an existing zone in the caller's company. Optional. */
    ZONE_CODE("zoneCode", false, "Optional. Code of an existing zone in this company."),

    /** Non-negative integer minutes. Blank defaults to 0. */
    SERVICE_TIME_MINUTES("serviceTimeMinutes", false, "Optional. Whole minutes, 0 or more. Defaults to 0."),

    /** Both-or-neither with {@link #EXTERNAL_REFERENCE}. */
    EXTERNAL_SYSTEM("externalSystem", false, "Optional. Needs externalReference too."),
    EXTERNAL_REFERENCE("externalReference", false, "Optional. Needs externalSystem too.");

    private final String header;
    private final boolean required;
    private final String helpText;

    LocationImportColumn(String header, boolean required, String helpText) {
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
            List.of(Arrays.stream(values()).map(LocationImportColumn::spec).toArray(ImportColumnSpec[]::new));
}
