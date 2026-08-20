package com.ebim.tms.shared.imports;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the header row of a raw grid, maps it against a set of {@link ImportColumnSpec}s, and
 * reads the rows below it into {@link ImportRow}s.
 *
 * <p>Generalizes {@code OrderImportParser}'s header-finding and row-mapping logic so every entity
 * import shares it instead of re-implementing it. What stays entity-specific is only the column
 * list itself and everything downstream of {@link ImportRow} (type conversion, business rules).
 */
public final class ImportGridMapper {

    /** How far down the sheet the header row is looked for before giving up. */
    private static final int HEADER_SEARCH_LIMIT = 20;

    private ImportGridMapper() {}

    public record ParsedGrid(List<String> columns, List<ImportRow> rows) {}

    /**
     * @param grid                the raw grid, as {@link ImportGrid#read} returns it
     * @param columns             every column the file may carry
     * @param maxRows             data rows allowed, excluding the header
     * @param subjectPluralNoun   what a row describes, for the "no header row found" message, e.g. "locations"
     */
    public static ParsedGrid map(
            List<List<String>> grid, List<ImportColumnSpec> columns, int maxRows, String subjectPluralNoun) {
        int headerIndex = findHeaderRow(grid, columns);
        if (headerIndex < 0) {
            String requiredHeaders = columns.stream()
                    .filter(ImportColumnSpec::required)
                    .map(ImportColumnSpec::header)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new InvalidRequestException("No header row was found. The file must contain a row with at least "
                    + "the " + requiredHeaders + " columns to describe " + subjectPluralNoun
                    + ". Download the template to see the expected shape.");
        }

        Map<Integer, ImportColumnSpec> byPosition = mapHeader(grid.get(headerIndex), columns);
        List<String> presentColumns = new ArrayList<>(new LinkedHashMap<>(byPosition).values().stream()
                .map(ImportColumnSpec::header).toList());
        for (ImportColumnSpec required : columns) {
            if (required.required() && !presentColumns.contains(required.header())) {
                throw new InvalidRequestException(
                        "The file is missing the required column '" + required.header() + "'.");
            }
        }

        List<ImportRow> rows = new ArrayList<>();
        for (int rowIndex = headerIndex + 1; rowIndex < grid.size(); rowIndex++) {
            ImportRow row = toRow(rowIndex + 1, grid.get(rowIndex), byPosition);
            if (row.isBlank()) {
                // A blank row inside the table is skipped rather than reported: spreadsheets are
                // full of them and none of them is an operator's mistake.
                continue;
            }
            rows.add(row);
            if (rows.size() > maxRows) {
                throw new InvalidRequestException("The file has more than " + maxRows
                        + " rows. Split it into smaller files, or use the integration API for a load this size.");
            }
        }
        if (rows.isEmpty()) {
            throw new InvalidRequestException("The file has a header but no data rows.");
        }
        return new ParsedGrid(List.copyOf(presentColumns), List.copyOf(rows));
    }

    /**
     * The header is the first row within {@link #HEADER_SEARCH_LIMIT} that resolves at least one
     * required column, so a file with a title or a blank line above the table still works.
     */
    private static int findHeaderRow(List<List<String>> grid, List<ImportColumnSpec> columns) {
        int limit = Math.min(grid.size(), HEADER_SEARCH_LIMIT);
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            for (String cell : grid.get(rowIndex)) {
                if (columns.stream().anyMatch(column -> column.required() && column.matchesHeader(cell))) {
                    return rowIndex;
                }
            }
        }
        return -1;
    }

    /**
     * Header cell positions to columns. An unrecognised header is ignored rather than rejected -
     * operators add their own notes columns - and a column repeated twice keeps its first
     * occurrence, since the second is far more likely to be a leftover than the intended one.
     */
    private static Map<Integer, ImportColumnSpec> mapHeader(List<String> headerCells, List<ImportColumnSpec> columns) {
        Map<Integer, ImportColumnSpec> byPosition = new LinkedHashMap<>();
        List<ImportColumnSpec> seen = new ArrayList<>();
        for (int position = 0; position < headerCells.size(); position++) {
            String cell = headerCells.get(position);
            for (ImportColumnSpec column : columns) {
                if (column.matchesHeader(cell) && !seen.contains(column)) {
                    seen.add(column);
                    byPosition.put(position, column);
                    break;
                }
            }
        }
        return byPosition;
    }

    private static ImportRow toRow(int rowNumber, List<String> cells, Map<Integer, ImportColumnSpec> byPosition) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, ImportColumnSpec> entry : byPosition.entrySet()) {
            int position = entry.getKey();
            if (position >= cells.size()) {
                continue;
            }
            String value = cells.get(position) == null ? "" : cells.get(position).trim();
            if (!value.isEmpty()) {
                values.put(entry.getValue().header(), value);
            }
        }
        return new ImportRow(rowNumber, values);
    }
}
