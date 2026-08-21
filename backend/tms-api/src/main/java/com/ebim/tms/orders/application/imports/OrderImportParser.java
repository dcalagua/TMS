package com.ebim.tms.orders.application.imports;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.io.DelimitedTextReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded XLSX or CSV into {@link OrderImportRow}s: locate the header, map each header
 * cell to an {@link OrderImportColumn}, and read every data row as trimmed text.
 *
 * <p>This class is the only place in the codebase that touches Apache POI. Everything downstream
 * works on {@code String} values, which is what makes {@code OrderImportValidator} testable
 * without ever constructing a workbook.
 *
 * <p>Failures here are structural - not a spreadsheet, no recognisable header, too many rows -
 * and are thrown as {@link InvalidRequestException}, because there is nothing to report per row
 * when the file's shape itself is unusable. Everything that <em>is</em> per row is a validation
 * issue instead, and never an exception.
 */
@Component
public class OrderImportParser {

    /** How far down the sheet the header row is looked for before giving up. */
    private static final int HEADER_SEARCH_LIMIT = 20;

    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** The result of a successful parse: which columns the file actually had, and its rows. */
    public record ParsedFile(List<OrderImportColumn> columns, List<OrderImportRow> rows) {
    }

    public ParsedFile parse(byte[] content, OrderImportFormat format) {
        List<List<String>> grid = switch (format) {
            case XLSX -> readWorkbook(content);
            case CSV -> DelimitedTextReader.read(content);
        };
        return toRows(grid);
    }

    /**
     * Reads the first sheet into a grid of strings. The first sheet, not a sheet found by name:
     * the template names it, but an operator who rebuilt the file in their own workbook did not,
     * and refusing that file would be a rule about naming rather than about data.
     */
    private static List<List<String>> readWorkbook(byte[] content) {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidRequestException("The workbook has no sheets.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> grid = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                grid.add(row == null ? List.of() : readRow(row));
            }
            return grid;
        } catch (IOException | RuntimeException failed) {
            if (failed instanceof InvalidRequestException invalid) {
                throw invalid;
            }
            // POI throws a variety of unchecked types for a corrupt or non-OOXML archive. The
            // caller gets one message; the cause stays in the server log, where it belongs.
            throw new InvalidRequestException(
                    "The file could not be read as an .xlsx workbook. Re-save it from the template and try again.");
        }
    }

    private static List<String> readRow(Row row) {
        List<String> cells = new ArrayList<>();
        for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
            cells.add(readCell(row.getCell(cellIndex)));
        }
        return cells;
    }

    /**
     * Reads one cell as the text an operator sees, which for two cell types is not what a naive
     * {@code toString} produces:
     *
     * <ul>
     *   <li><b>A date cell</b> is a number in the file. Formatted with {@code toString} it comes
     *       back as {@code 46082.0} - a serial number no validator can interpret - so a
     *       date-formatted numeric cell is rendered as ISO {@code yyyy-MM-dd} and a time-formatted
     *       one as {@code HH:mm}. This is the single most common way a spreadsheet import fails.</li>
     *   <li><b>A whole number</b> comes back as {@code 12.0}, because every numeric cell in a
     *       workbook is a double. {@link BigDecimal#stripTrailingZeros()} restores {@code 12},
     *       which matters for a quantity an operator will read back in an error message.</li>
     * </ul>
     */
    private static String readCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> readNumeric(cell);
            default -> "";
        };
    }

    private static String readNumeric(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            var dateTime = cell.getLocalDateTimeCellValue();
            if (dateTime == null) {
                return "";
            }
            // A cell holding only a time has the spreadsheet epoch as its date part; one holding
            // a date has midnight as its time part. Which of the two it is decides the format.
            LocalDate date = dateTime.toLocalDate();
            LocalTime time = dateTime.toLocalTime();
            if (date.getYear() <= 1900) {
                return time.format(ISO_TIME);
            }
            return date.toString();
        }
        return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }

    /**
     * Finds the header row, maps it, and reads the rows below it. The header is the first row
     * within {@link #HEADER_SEARCH_LIMIT} that resolves at least one required column, so a file
     * with a title or a blank line above the table still works.
     */
    private static ParsedFile toRows(List<List<String>> grid) {
        int headerIndex = findHeaderRow(grid);
        if (headerIndex < 0) {
            throw new InvalidRequestException("No header row was found. The file must contain a row with at least the "
                    + OrderImportColumn.EXTERNAL_REFERENCE.header() + ", " + OrderImportColumn.ORIGIN_CODE.header()
                    + ", " + OrderImportColumn.DESTINATION_CODE.header() + " and "
                    + OrderImportColumn.SERVICE_DATE.header() + " columns. Download the template to see the expected shape.");
        }

        Map<Integer, OrderImportColumn> byPosition = mapHeader(grid.get(headerIndex));
        List<OrderImportColumn> columns = List.copyOf(new LinkedHashMap<>(byPosition).values());
        for (OrderImportColumn required : OrderImportColumn.values()) {
            if (required.required() && !columns.contains(required)) {
                throw new InvalidRequestException(
                        "The file is missing the required column '" + required.header() + "'.");
            }
        }

        List<OrderImportRow> rows = new ArrayList<>();
        for (int rowIndex = headerIndex + 1; rowIndex < grid.size(); rowIndex++) {
            OrderImportRow row = toRow(rowIndex + 1, grid.get(rowIndex), byPosition);
            if (row.isBlank()) {
                // A blank row inside the table is skipped rather than reported: spreadsheets are
                // full of them and none of them is an operator's mistake.
                continue;
            }
            rows.add(row);
            if (rows.size() > OrderImportLimits.MAX_ROWS) {
                throw new InvalidRequestException("The file has more than " + OrderImportLimits.MAX_ROWS
                        + " rows. Split it into smaller files, or use the integration API for a load this size.");
            }
        }
        if (rows.isEmpty()) {
            throw new InvalidRequestException("The file has a header but no data rows.");
        }
        return new ParsedFile(columns, List.copyOf(rows));
    }

    private static int findHeaderRow(List<List<String>> grid) {
        int limit = Math.min(grid.size(), HEADER_SEARCH_LIMIT);
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            for (String cell : grid.get(rowIndex)) {
                OrderImportColumn column = OrderImportColumn.forHeader(cell);
                if (column != null && column.required()) {
                    return rowIndex;
                }
            }
        }
        return -1;
    }

    /**
     * Header cell positions to columns. An unrecognised header is ignored rather than rejected -
     * operators add their own notes columns, and refusing the file over one would be hostile -
     * and a column repeated twice keeps its first occurrence, because the second is far more
     * likely to be a leftover than the intended one.
     */
    private static Map<Integer, OrderImportColumn> mapHeader(List<String> headerCells) {
        Map<Integer, OrderImportColumn> byPosition = new LinkedHashMap<>();
        Map<OrderImportColumn, Integer> seen = new EnumMap<>(OrderImportColumn.class);
        for (int position = 0; position < headerCells.size(); position++) {
            OrderImportColumn column = OrderImportColumn.forHeader(headerCells.get(position));
            if (column != null && !seen.containsKey(column)) {
                seen.put(column, position);
                byPosition.put(position, column);
            }
        }
        return byPosition;
    }

    private static OrderImportRow toRow(int rowNumber, List<String> cells, Map<Integer, OrderImportColumn> byPosition) {
        Map<OrderImportColumn, String> values = new EnumMap<>(OrderImportColumn.class);
        for (Map.Entry<Integer, OrderImportColumn> entry : byPosition.entrySet()) {
            int position = entry.getKey();
            if (position >= cells.size()) {
                continue;
            }
            String value = cells.get(position) == null ? "" : cells.get(position).trim();
            if (!value.isEmpty()) {
                values.put(entry.getValue(), value);
            }
        }
        return new OrderImportRow(rowNumber, values);
    }
}
