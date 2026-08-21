package com.ebim.tms.shared.settings;

/**
 * One company's operational defaults, as the rest of the product reads them (migration V34).
 *
 * <p>A value type in {@code shared} rather than an entity in {@code iam}, for the reason
 * {@link com.ebim.tms.shared.audit.AuditRecorder} is an interface in {@code shared}: three business
 * modules consume these values ({@code orders}, {@code planning}, {@code masterdata}) and
 * {@code ModuleBoundaryTest} forbids every one of them from importing {@code com.ebim.tms.iam}.
 *
 * <p>The constants are the values TMS used as literals before V34 - {@code "PE"} in
 * {@code LocationImportValidator}, {@code "TO-"} in {@code OrderNumbers}, {@code "SH-"} in
 * {@code TripService} - so {@link #defaults()} is exactly the behaviour of the product before a
 * company edited anything. That matters beyond tidiness: it is what lets
 * {@link CompanySettingsPort} answer for a company whose settings row is missing without any
 * caller having to handle an empty {@code Optional} in the middle of creating an order.
 */
public record CompanySettings(
        String defaultCountry,
        String orderNumberPrefix,
        String shipmentNumberPrefix) {

    /** ISO 3166-1 alpha-2 of the launch market, and the default of {@code tms.location.country}. */
    public static final String DEFAULT_COUNTRY = "PE";

    public static final String DEFAULT_ORDER_NUMBER_PREFIX = "TO-";

    public static final String DEFAULT_SHIPMENT_NUMBER_PREFIX = "SH-";

    private static final CompanySettings DEFAULTS = new CompanySettings(
            DEFAULT_COUNTRY, DEFAULT_ORDER_NUMBER_PREFIX, DEFAULT_SHIPMENT_NUMBER_PREFIX);

    /**
     * Blank-tolerant on purpose. The columns behind this are all {@code NOT NULL} with defaults, so
     * a null here means "no row" rather than "a row with a hole in it" - and the honest response to
     * no row is the behaviour the product had before the row existed, not a failed order creation.
     */
    public CompanySettings {
        defaultCountry = orDefault(defaultCountry, DEFAULT_COUNTRY);
        orderNumberPrefix = orDefault(orderNumberPrefix, DEFAULT_ORDER_NUMBER_PREFIX);
        shipmentNumberPrefix = orDefault(shipmentNumberPrefix, DEFAULT_SHIPMENT_NUMBER_PREFIX);
    }

    /** What a company that has never edited its settings gets, and what a missing row resolves to. */
    public static CompanySettings defaults() {
        return DEFAULTS;
    }

    /**
     * Trims, and falls back when there is nothing left. No case folding here: normalizing is the
     * writing service's job, where a value that cannot be normalized is still reportable to the
     * person who typed it. A reader that silently upper-cased what it found would hide a bad row
     * instead of surfacing it.
     */
    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
