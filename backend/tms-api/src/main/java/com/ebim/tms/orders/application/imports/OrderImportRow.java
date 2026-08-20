package com.ebim.tms.orders.application.imports;

import java.util.Map;

/**
 * One data row of an uploaded file, still entirely as text.
 *
 * <p>Nothing is converted here on purpose. A row that fails to parse must still be reportable -
 * "row 14, quantity: 'twelve' is not a number" needs the original text - so conversion happens
 * in {@code OrderImportValidator}, where a failure becomes an issue rather than an exception.
 *
 * @param rowNumber the 1-based row number <em>as the operator sees it in the spreadsheet</em>,
 *                  header row included. This is the number that goes into an error message, so
 *                  it must match what the operator's own application shows, not a zero-based
 *                  index into a data list.
 * @param values    trimmed cell text by column; a column absent from the file or blank in this
 *                  row is simply not a key
 */
public record OrderImportRow(int rowNumber, Map<OrderImportColumn, String> values) {

    public OrderImportRow {
        values = Map.copyOf(values);
    }

    /** The trimmed value, or {@code null} when the column was absent or blank. */
    public String value(OrderImportColumn column) {
        return values.get(column);
    }

    public boolean hasValue(OrderImportColumn column) {
        return values.containsKey(column);
    }

    /** True when the row carries nothing at all - a blank line an operator left in the sheet. */
    public boolean isBlank() {
        return values.isEmpty();
    }
}
