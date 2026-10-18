package com.ebim.tms.settlement.domain;

import java.util.regex.Pattern;

/**
 * The shape of a CPE series and correlative (V66, ADR-024 part 2, item 3), the same patterns as
 * {@code ck_carrier_invoice_series} and {@code ck_carrier_invoice_correlative}, checked here first so
 * the refusal is a sentence rather than a constraint name.
 */
public final class CpeNumbering {

    /** {@code F} + 3 alphanumerics (electronic), {@code E001} (SUNAT portal) or 4 digits (contingency). */
    private static final Pattern SERIES = Pattern.compile("^(F[A-Z0-9]{3}|E001|[0-9]{4})$");

    private static final Pattern CORRELATIVE = Pattern.compile("^[0-9]{1,8}$");

    private CpeNumbering() {
    }

    public static boolean isValidSeries(String series) {
        return series != null && SERIES.matcher(series).matches();
    }

    /**
     * The correlative without leading zeros, so {@code 00000123} and {@code 123} are one document
     * under {@code uq_carrier_invoice_cpe}; null when it is not 1-8 digits or is zero.
     */
    public static String normalizeCorrelative(String correlative) {
        if (correlative == null || !CORRELATIVE.matcher(correlative).matches()) {
            return null;
        }
        String stripped = correlative.replaceFirst("^0+", "");
        return stripped.isEmpty() ? null : stripped;
    }

    /** The printed number, {@code F001-123}: what {@code invoice_number} becomes with a CPE identity. */
    public static String invoiceNumberOf(String series, String correlative) {
        return series + "-" + correlative;
    }
}
