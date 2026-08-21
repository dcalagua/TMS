package com.ebim.tms.shared.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

/**
 * Builds the downloadable import template - in both formats every import accepts - from a column
 * list rather than a file kept in {@code resources}: a checked-in workbook drifts from the parser
 * the first time a column is added, and generated, the two cannot disagree. Generalizes
 * {@code OrderImportTemplate} so Locations, Carriers, Vehicle Types and Vehicles share one writer.
 */
public final class ImportTemplateWriter {

    private ImportTemplateWriter() {}

    /**
     * @param dataSheetName     the sheet an operator fills in, e.g. "Locations"
     * @param columns           the file's columns, in write order
     * @param exampleRows       zero or more example rows, one value per column in {@code columns} order
     * @param instructionLines  free-text lines shown above the column reference table on the Instructions sheet
     */
    public static byte[] build(ImportFormat format, String dataSheetName, List<ImportColumnSpec> columns,
            List<List<String>> exampleRows, List<String> instructionLines) {
        return switch (format) {
            case XLSX -> buildWorkbook(dataSheetName, columns, exampleRows, instructionLines);
            case CSV -> buildCsv(columns, exampleRows);
        };
    }

    public static String fileName(String entityNamePlural, ImportFormat format) {
        return "tms-" + entityNamePlural + "-import-template." + format.extension();
    }

    private static byte[] buildCsv(List<ImportColumnSpec> columns, List<List<String>> exampleRows) {
        StringBuilder csv = new StringBuilder();
        // A UTF-8 BOM, deliberately: without it Excel on Windows opens the file in the machine's
        // ANSI code page and every accented character is mangled. DelimitedTextReader strips it
        // on the way back in, so this costs nothing. Written as an escape (U+FEFF) rather than the
        // literal character, which is invisible in a source file.
        csv.append((char) 0xFEFF);
        appendCsvRow(csv, columns.stream().map(ImportColumnSpec::header).toList());
        for (List<String> row : exampleRows) {
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

    private static byte[] buildWorkbook(String dataSheetName, List<ImportColumnSpec> columns,
            List<List<String>> exampleRows, List<String> instructionLines) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeDataSheet(workbook, dataSheetName, columns, exampleRows);
            writeInstructionsSheet(workbook, columns, instructionLines);
            workbook.setActiveSheet(workbook.getSheetIndex("Instructions"));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failed) {
            // Writing to a byte array cannot fail for any reason a caller could act on.
            throw new UncheckedIOException("could not build the import template", failed);
        }
    }

    private static void writeDataSheet(
            Workbook workbook, String sheetName, List<ImportColumnSpec> columns, List<List<String>> exampleRows) {
        Sheet sheet = workbook.createSheet(sheetName);
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle exampleStyle = exampleStyle(workbook);

        Row header = sheet.createRow(0);
        for (int index = 0; index < columns.size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(columns.get(index).header());
            cell.setCellStyle(headerStyle);
        }

        int rowIndex = 1;
        for (List<String> values : exampleRows) {
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

        for (int index = 0; index < columns.size(); index++) {
            sheet.autoSizeColumn(index);
            sheet.setColumnWidth(index, Math.min(sheet.getColumnWidth(index) + 512, 24 * 256));
        }
        sheet.createFreezePane(0, 1);
    }

    private static void writeInstructionsSheet(
            Workbook workbook, List<ImportColumnSpec> columns, List<String> instructionLines) {
        Sheet sheet = workbook.createSheet("Instructions");
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle wrapStyle = workbook.createCellStyle();
        wrapStyle.setWrapText(true);
        wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

        int rowIndex = 0;
        for (String line : instructionLines) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(line);
        }

        Row columnHeader = sheet.createRow(rowIndex++);
        List<String> headerCells = List.of("Column", "Required", "Notes");
        for (int index = 0; index < headerCells.size(); index++) {
            Cell cell = columnHeader.createCell(index);
            cell.setCellValue(headerCells.get(index));
            cell.setCellStyle(headerStyle);
        }

        for (ImportColumnSpec column : columns) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(column.header());
            row.createCell(1).setCellValue(column.required() ? "Yes" : "No");
            Cell notes = row.createCell(2);
            notes.setCellValue(column.helpText());
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
}
