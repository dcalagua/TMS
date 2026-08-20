package com.ebim.tms.orders.application.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Generates the downloadable import template, in both formats the import accepts.
 *
 * <p>The template is produced from {@link OrderImportColumn} rather than kept as a file in
 * {@code resources}: a checked-in workbook drifts from the parser the first time a column is
 * added, and the drift is invisible until an operator downloads it. Generated, the two cannot
 * disagree.
 *
 * <p>The XLSX carries three things a CSV cannot: a frozen, styled header row, an
 * {@code Instructions} sheet explaining every column in the operator's own terms, and example
 * rows that demonstrate the two shapes an order can take - one with lines, and one header-only
 * with declared totals. Those examples are the fastest way to communicate the grouping rule,
 * which is the part of this format operators get wrong.
 */
@Component
public class OrderImportTemplate {

    private static final String DATA_SHEET = "Orders";
    private static final String INSTRUCTIONS_SHEET = "Instructions";

    /** Per-column help, rendered on the Instructions sheet. Keyed so a missing entry is obvious. */
    private static final Map<OrderImportColumn, String> HELP = Map.ofEntries(
            Map.entry(OrderImportColumn.EXTERNAL_REFERENCE,
                    "Your own order number. Rows sharing one value become ONE order with several lines. "
                            + "Re-importing a value that already exists is skipped, not duplicated."),
            Map.entry(OrderImportColumn.ORIGIN_CODE, "Code of the origin the goods ship from. Must be active."),
            Map.entry(OrderImportColumn.DESTINATION_CODE, "Code of the destination or store. Must be active."),
            Map.entry(OrderImportColumn.CUSTOMER_NAME, "Optional. Free text."),
            Map.entry(OrderImportColumn.CUSTOMER_REFERENCE, "Optional. The customer's purchase order, for example."),
            Map.entry(OrderImportColumn.SERVICE_DATE, "Required date, as yyyy-MM-dd. dd/MM/yyyy is read day first."),
            Map.entry(OrderImportColumn.PRIORITY, "LOW, NORMAL, HIGH or URGENT. Blank means NORMAL."),
            Map.entry(OrderImportColumn.WINDOW_START, "Optional delivery window start, HH:mm. Needs an end too."),
            Map.entry(OrderImportColumn.WINDOW_END, "Optional delivery window end, HH:mm. Must be later than the start."),
            Map.entry(OrderImportColumn.DECLARED_WEIGHT_KG,
                    "Optional. The total weight you assert. Leave blank when the lines carry unit weights: "
                            + "where both are given they must agree within 1%."),
            Map.entry(OrderImportColumn.DECLARED_VOLUME_M3, "Optional. The total volume you assert. See declaredWeightKg."),
            Map.entry(OrderImportColumn.DECLARED_PALLETS, "Optional. The total pallets you assert. See declaredWeightKg."),
            Map.entry(OrderImportColumn.MATERIAL_CODE, "Leave the material columns blank for an order with no line detail."),
            Map.entry(OrderImportColumn.MATERIAL_DESCRIPTION, "Optional. Defaults to the material code."),
            Map.entry(OrderImportColumn.QUANTITY, "Required on a line. Greater than zero. Use a dot: 12.5, never 1,234."),
            Map.entry(OrderImportColumn.UOM, "Required on a line. For example EA, CS, KG, PAL."),
            Map.entry(OrderImportColumn.UNIT_WEIGHT_KG, "Optional. Weight of ONE unit, not of the line."),
            Map.entry(OrderImportColumn.UNIT_VOLUME_M3, "Optional. Volume of ONE unit, not of the line."),
            Map.entry(OrderImportColumn.PALLET_QUANTITY, "Optional. Pallets this line occupies. Not derived from quantity."));

    /**
     * Two example orders: {@code EXAMPLE-1} has two lines and no declared totals, {@code
     * EXAMPLE-2} is header-only with declared totals. Between them they show the grouping rule
     * and both totals strategies without a word of prose.
     */
    private static List<List<String>> exampleRows(LocalDate serviceDate) {
        String date = serviceDate.toString();
        return List.of(
                List.of("EXAMPLE-1", "ORIGIN-01", "STORE-01", "Acme Retail", "PO-88123", date, "NORMAL", "08:00",
                        "12:00", "", "", "", "SKU-1001", "Bottled water 1L x12", "20", "CS", "12.5", "0.018", "0.5"),
                List.of("EXAMPLE-1", "ORIGIN-01", "STORE-01", "Acme Retail", "PO-88123", date, "NORMAL", "08:00",
                        "12:00", "", "", "", "SKU-1002", "Cereal box 500g", "35", "CS", "6", "0.012", "0.5"),
                List.of("EXAMPLE-2", "ORIGIN-01", "STORE-02", "Acme Retail", "PO-88124", date, "HIGH", "", "",
                        "1200", "3.4", "2", "", "", "", "", "", "", ""));
    }

    public byte[] build(OrderImportFormat format, LocalDate exampleServiceDate) {
        return switch (format) {
            case XLSX -> buildWorkbook(exampleServiceDate);
            case CSV -> buildCsv(exampleServiceDate);
        };
    }

    /** The file name the browser should save it under - one definition, used by the controller. */
    public String fileName(OrderImportFormat format) {
        return "tms-order-import-template." + format.extension();
    }

    private static byte[] buildCsv(LocalDate exampleServiceDate) {
        StringBuilder csv = new StringBuilder();
        // A UTF-8 BOM, deliberately: without it Excel on Windows opens the file in the machine's
        // ANSI code page and every accented character in a description is mangled. The reader on
        // the way back in strips it (DelimitedTextReader), so this costs nothing.
        csv.append('\uFEFF');
        appendCsvRow(csv, headers());
        for (List<String> row : exampleRows(exampleServiceDate)) {
            appendCsvRow(csv, row);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsvRow(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            String value = values.get(index);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
                csv.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                csv.append(value);
            }
        }
        csv.append("\r\n");
    }

    private static byte[] buildWorkbook(LocalDate exampleServiceDate) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeDataSheet(workbook, exampleServiceDate);
            writeInstructionsSheet(workbook);
            workbook.setActiveSheet(workbook.getSheetIndex(INSTRUCTIONS_SHEET));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failed) {
            // Writing to a byte array cannot fail for any reason a caller could act on.
            throw new UncheckedIOException("could not build the order import template", failed);
        }
    }

    private static void writeDataSheet(Workbook workbook, LocalDate exampleServiceDate) {
        Sheet sheet = workbook.createSheet(DATA_SHEET);
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle exampleStyle = exampleStyle(workbook);

        Row header = sheet.createRow(0);
        List<String> headers = headers();
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(headerStyle);
        }

        int rowIndex = 1;
        for (List<String> values : exampleRows(exampleServiceDate)) {
            Row row = sheet.createRow(rowIndex++);
            for (int index = 0; index < values.size(); index++) {
                Cell cell = row.createCell(index);
                // Written as text, including the numbers: the import parses text, and a template
                // whose example rows are text cannot teach an operator a formatting habit the
                // parser then rejects.
                cell.setCellValue(values.get(index));
                cell.setCellStyle(exampleStyle);
            }
        }

        for (int index = 0; index < headers.size(); index++) {
            sheet.autoSizeColumn(index);
            // autoSizeColumn measures the example text, which for a description column is wider
            // than any operator wants; cap it so the sheet opens readable.
            sheet.setColumnWidth(index, Math.min(sheet.getColumnWidth(index) + 512, 24 * 256));
        }
        sheet.createFreezePane(0, 1);
    }

    private static void writeInstructionsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet(INSTRUCTIONS_SHEET);
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle wrapStyle = workbook.createCellStyle();
        wrapStyle.setWrapText(true);
        wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

        int rowIndex = 0;
        for (String line : List.of(
                "TMS by EBIM - transport order import",
                "",
                "1. Fill the 'Orders' sheet. Delete the EXAMPLE rows before uploading.",
                "2. One row is one order LINE. Rows that share the same externalReference become one order.",
                "3. For an order with no line detail, use a single row and fill the declared* columns instead.",
                "4. Upload the file and review the preview. Nothing is saved until you confirm.",
                "5. If any row has an error, nothing at all is imported - fix the file and upload it again.",
                "6. Re-uploading a file is safe: orders whose externalReference already exists are skipped.",
                "",
                "Columns")) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(line);
        }

        Row columnHeader = sheet.createRow(rowIndex++);
        for (int index = 0; index < 3; index++) {
            Cell cell = columnHeader.createCell(index);
            cell.setCellValue(List.of("Column", "Required", "Notes").get(index));
            cell.setCellStyle(headerStyle);
        }

        for (OrderImportColumn column : OrderImportColumn.values()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(column.header());
            row.createCell(1).setCellValue(column.required() ? "Yes" : "No");
            Cell notes = row.createCell(2);
            notes.setCellValue(HELP.getOrDefault(column, ""));
            notes.setCellStyle(wrapStyle);
        }

        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 10 * 256);
        sheet.setColumnWidth(2, 90 * 256);
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private static CellStyle exampleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font italic = workbook.createFont();
        italic.setItalic(true);
        italic.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(italic);
        return style;
    }

    private static List<String> headers() {
        return Arrays.stream(OrderImportColumn.values()).map(OrderImportColumn::header).toList();
    }
}
