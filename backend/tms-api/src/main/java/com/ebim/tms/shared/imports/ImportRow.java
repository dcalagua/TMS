package com.ebim.tms.shared.imports;

import java.util.Map;

/**
 * One data row of an uploaded file, still entirely as text, keyed by {@link ImportColumnSpec#header()}.
 *
 * <p>Nothing is converted here on purpose. A row that fails to parse must still be reportable -
 * "row 14, maxWeightKg: 'heavy' is not a number" needs the original text - so conversion happens
 * in each entity's own validator, where a failure becomes an issue rather than an exception.
 *
 * @param rowNumber the 1-based row number <em>as the operator sees it in the spreadsheet</em>,
 *                  header row included
 * @param values    trimmed cell text by column header; a column absent from the file or blank in
 *                  this row is simply not a key
 */
public record ImportRow(int rowNumber, Map<String, String> values) {

    public ImportRow {
        values = Map.copyOf(values);
    }

    /** The trimmed value, or {@code null} when the column was absent or blank. */
    public String value(String header) {
        return values.get(header);
    }

    public String value(ImportColumnSpec column) {
        return value(column.header());
    }

    public boolean hasValue(String header) {
        return values.containsKey(header);
    }

    public boolean hasValue(ImportColumnSpec column) {
        return hasValue(column.header());
    }

    /** True when the row carries nothing at all - a blank line an operator left in the sheet. */
    public boolean isBlank() {
        return values.isEmpty();
    }
}
