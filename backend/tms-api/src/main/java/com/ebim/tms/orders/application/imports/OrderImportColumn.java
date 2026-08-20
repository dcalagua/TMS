package com.ebim.tms.orders.application.imports;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The columns of the bulk order import file, in the order the template writes them. One enum
 * drives three things that must never drift apart: the generated template's header row, the
 * header matching of an uploaded file, and the per-column help text an operator reads.
 *
 * <h2>Why one row is one order <em>line</em></h2>
 *
 * <p>An order has lines, so a file that carried one row per order could only import orders
 * without line detail. Instead every row is a line, and rows sharing the same
 * {@link #EXTERNAL_REFERENCE} are one order - the shape every ERP export already has, because
 * it is the shape a relational extract produces. The header columns are repeated on each row of
 * a group and must agree; {@code OrderImportParser} reports a row where they do not rather than
 * silently taking the first.
 *
 * <p>A row whose {@link #MATERIAL_CODE} is blank contributes no line. That is how a header-only
 * order - the "1,200 kg, no detail" payload {@code OrderTotals} exists for - is expressed: one
 * row, the declared totals filled in, the material columns empty.
 */
public enum OrderImportColumn {

    /**
     * The sender's own identifier for the order, and the grouping key. Required: it is the
     * second half of the {@code (external_source, external_reference)} pair that makes the whole
     * import idempotent, so a file without one could not be safely re-uploaded.
     */
    EXTERNAL_REFERENCE("externalReference", true),

    /** Code of an active origin in the caller's company - the ship-from location. */
    ORIGIN_CODE("originCode", true),

    /** Code of an active destination in the caller's company - the ship-to/store location. */
    DESTINATION_CODE("destinationCode", true),

    CUSTOMER_NAME("customerName", false),
    CUSTOMER_REFERENCE("customerReference", false),

    /** ISO {@code yyyy-MM-dd}. An XLSX date cell is accepted as a date, not as its serial number. */
    SERVICE_DATE("serviceDate", true),

    /** {@code LOW}, {@code NORMAL}, {@code HIGH} or {@code URGENT}. Blank means {@code NORMAL}. */
    PRIORITY("priority", false),

    /** {@code HH:mm}. Both window columns must be filled or both left blank. */
    WINDOW_START("windowStart", false),
    WINDOW_END("windowEnd", false),

    /** Declared header figures - see {@code OrderTotals} for how they interact with the lines. */
    DECLARED_WEIGHT_KG("declaredWeightKg", false),
    DECLARED_VOLUME_M3("declaredVolumeM3", false),
    DECLARED_PALLETS("declaredPallets", false),

    /** Blank on a row that carries no line; required as a set with the three columns after it. */
    MATERIAL_CODE("materialCode", false),
    MATERIAL_DESCRIPTION("materialDescription", false),
    QUANTITY("quantity", false),
    UOM("uom", false),

    UNIT_WEIGHT_KG("unitWeightKg", false),
    UNIT_VOLUME_M3("unitVolumeM3", false),
    PALLET_QUANTITY("palletQuantity", false);

    private static final Map<String, OrderImportColumn> BY_NORMALIZED_HEADER = buildIndex();

    private final String header;
    private final boolean required;

    OrderImportColumn(String header, boolean required) {
        this.header = header;
        this.required = required;
    }

    /** The exact text the template writes, and what an uploaded file is expected to carry. */
    public String header() {
        return header;
    }

    /** True when a value is required on every row, header column or not. */
    public boolean required() {
        return required;
    }

    /**
     * Matches a header cell from an uploaded file. Case, surrounding whitespace and internal
     * spaces, underscores and hyphens are ignored, so {@code "Origin Code"}, {@code origin_code}
     * and {@code ORIGINCODE} all resolve - an operator who reformatted the template's header row
     * has not corrupted the file, and telling them they have would be pedantry, not validation.
     */
    public static OrderImportColumn forHeader(String header) {
        return header == null ? null : BY_NORMALIZED_HEADER.get(normalize(header));
    }

    private static String normalize(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    private static Map<String, OrderImportColumn> buildIndex() {
        Map<String, OrderImportColumn> index = new LinkedHashMap<>();
        for (OrderImportColumn column : values()) {
            index.put(normalize(column.header), column);
        }
        return Map.copyOf(index);
    }
}
