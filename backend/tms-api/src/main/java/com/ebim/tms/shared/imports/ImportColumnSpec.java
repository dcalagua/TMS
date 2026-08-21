package com.ebim.tms.shared.imports;

import java.util.Locale;

/**
 * One column of a bulk import file: the exact header text the template writes and an uploaded
 * file is matched against, whether it is required, and the help text shown on the template's
 * Instructions sheet.
 *
 * <p>Every entity import (Location, Carrier, VehicleType, Vehicle - Order before them, inside its
 * own package) keeps its columns as an enum for type-safe reference in validator code, and
 * exposes them as a {@code List<ImportColumnSpec>} to the shared reader/mapper/template writer in
 * this package, which know nothing about any particular entity's fields.
 */
public record ImportColumnSpec(String header, boolean required, String helpText) {

    public ImportColumnSpec {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("An import column must have a header.");
        }
    }

    public ImportColumnSpec(String header, boolean required) {
        this(header, required, "");
    }

    /**
     * Matches a header cell from an uploaded file against {@link #header()}. Case, surrounding
     * whitespace and internal spaces, underscores and hyphens are ignored, so {@code "Origin Code"},
     * {@code origin_code} and {@code ORIGINCODE} all resolve - an operator who reformatted the
     * template's header row has not corrupted the file.
     */
    public boolean matchesHeader(String candidate) {
        return normalize(header).equals(normalize(candidate));
    }

    static String normalize(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }
}
