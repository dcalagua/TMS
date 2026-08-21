package com.ebim.tms.orders.application.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderImportParser} against both formats, including the workbook quirks that make a
 * spreadsheet import fail in practice: a date cell that is really a number, a whole number that
 * renders as {@code 12.0}, a title row above the table and a reordered header.
 *
 * <p>No Spring context and no database - POI runs anywhere, so these hold on a machine with no
 * Docker.
 */
class OrderImportParserTest {

    private static final OrderImportParser PARSER = new OrderImportParser();

    private static final String HEADER =
            "externalReference,originCode,destinationCode,serviceDate,materialCode,quantity,uom";

    private static OrderImportParser.ParsedFile parseCsv(String text) {
        return PARSER.parse(text.getBytes(StandardCharsets.UTF_8), OrderImportFormat.CSV);
    }

    // --- CSV -----------------------------------------------------------------------

    @Test
    @DisplayName("a CSV maps its header to columns and reads the rows below it")
    void csvRows() {
        OrderImportParser.ParsedFile parsed = parseCsv(HEADER + "\nORD-1,LIM-01,STORE-1,2026-03-01,SKU-1,12,EA\n");

        assertThat(parsed.columns()).containsExactly(OrderImportColumn.EXTERNAL_REFERENCE,
                OrderImportColumn.ORIGIN_CODE, OrderImportColumn.DESTINATION_CODE, OrderImportColumn.SERVICE_DATE,
                OrderImportColumn.MATERIAL_CODE, OrderImportColumn.QUANTITY, OrderImportColumn.UOM);
        assertThat(parsed.rows()).hasSize(1);

        OrderImportRow row = parsed.rows().get(0);
        // Row 2 as the operator sees it: the header is row 1.
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.value(OrderImportColumn.EXTERNAL_REFERENCE)).isEqualTo("ORD-1");
        assertThat(row.value(OrderImportColumn.QUANTITY)).isEqualTo("12");
    }

    @Test
    @DisplayName("header matching ignores case, spaces, underscores and hyphens")
    void headerMatchingIsForgiving() {
        OrderImportParser.ParsedFile parsed = parseCsv(
                "External Reference,ORIGIN_CODE,destination-code,ServiceDate\nORD-1,LIM-01,STORE-1,2026-03-01\n");

        assertThat(parsed.columns()).containsExactly(OrderImportColumn.EXTERNAL_REFERENCE,
                OrderImportColumn.ORIGIN_CODE, OrderImportColumn.DESTINATION_CODE, OrderImportColumn.SERVICE_DATE);
    }

    @Test
    @DisplayName("columns may be reordered and unknown columns are ignored rather than rejected")
    void reorderedAndExtraColumns() {
        OrderImportParser.ParsedFile parsed = parseCsv(
                "notes,serviceDate,destinationCode,originCode,externalReference\n"
                        + "ignore me,2026-03-01,STORE-1,LIM-01,ORD-1\n");

        OrderImportRow row = parsed.rows().get(0);
        assertThat(row.value(OrderImportColumn.EXTERNAL_REFERENCE)).isEqualTo("ORD-1");
        assertThat(row.value(OrderImportColumn.ORIGIN_CODE)).isEqualTo("LIM-01");
    }

    @Test
    @DisplayName("a title row above the table does not prevent the header from being found")
    void headerBelowATitle() {
        OrderImportParser.ParsedFile parsed = parseCsv(
                "Orders for week 12\n\n" + HEADER + "\nORD-1,LIM-01,STORE-1,2026-03-01,SKU-1,12,EA\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).rowNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("blank rows inside the table are skipped, not reported")
    void blankRowsAreSkipped() {
        assertThat(parseCsv(HEADER + "\nORD-1,LIM-01,STORE-1,2026-03-01,SKU-1,12,EA\n,,,,,,\n").rows()).hasSize(1);
    }

    @Test
    @DisplayName("a row shorter than the header simply has no value for the missing columns")
    void shortRow() {
        OrderImportRow row = parseCsv(HEADER + "\nORD-1,LIM-01,STORE-1,2026-03-01\n").rows().get(0);

        assertThat(row.value(OrderImportColumn.SERVICE_DATE)).isEqualTo("2026-03-01");
        assertThat(row.hasValue(OrderImportColumn.QUANTITY)).isFalse();
    }

    // --- structural failures ---------------------------------------------------------

    @Test
    @DisplayName("a file with no recognisable header is refused with an explanation, not a stack trace")
    void noHeader() {
        assertThatThrownBy(() -> parseCsv("alpha,beta\n1,2\n"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("No header row was found");
    }

    @Test
    @DisplayName("a header missing a required column names the column")
    void missingRequiredColumn() {
        assertThatThrownBy(() -> parseCsv("externalReference,originCode,serviceDate\nORD-1,LIM-01,2026-03-01\n"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("destinationCode");
    }

    @Test
    @DisplayName("a header with no data rows is refused")
    void headerOnly() {
        assertThatThrownBy(() -> parseCsv(HEADER + "\n"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no data rows");
    }

    @Test
    @DisplayName("a file beyond the row limit is refused rather than processed")
    void tooManyRows() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int index = 0; index <= OrderImportLimits.MAX_ROWS; index++) {
            csv.append("ORD-").append(index).append(",LIM-01,STORE-1,2026-03-01,SKU-1,1,EA\n");
        }

        assertThatThrownBy(() -> parseCsv(csv.toString()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("more than " + OrderImportLimits.MAX_ROWS + " rows");
    }

    @Test
    @DisplayName("bytes that are not a workbook are refused with a readable message")
    void notAWorkbook() {
        assertThatThrownBy(() -> PARSER.parse("not a workbook".getBytes(StandardCharsets.UTF_8),
                OrderImportFormat.XLSX))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(".xlsx workbook");
    }

    // --- XLSX ------------------------------------------------------------------------

    @Test
    @DisplayName("a real date cell is read as an ISO date, not as its serial number")
    void dateCellsAreNotSerialNumbers() {
        // The single most common spreadsheet import failure: 2026-03-01 stored as 46082.
        byte[] workbook = workbook(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("externalReference");
            header.createCell(1).setCellValue("originCode");
            header.createCell(2).setCellValue("destinationCode");
            header.createCell(3).setCellValue("serviceDate");
            header.createCell(4).setCellValue("quantity");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ORD-1");
            row.createCell(1).setCellValue("LIM-01");
            row.createCell(2).setCellValue("STORE-1");
            var dateCell = row.createCell(3);
            dateCell.setCellValue(LocalDate.of(2026, 3, 1));
            dateCell.setCellStyle(dateStyle(sheet.getWorkbook()));
            row.createCell(4).setCellValue(12d);
        });

        OrderImportRow row = PARSER.parse(workbook, OrderImportFormat.XLSX).rows().get(0);
        assertThat(row.value(OrderImportColumn.SERVICE_DATE)).isEqualTo("2026-03-01");
        // And a whole number is 12, not 12.0 - which an operator would see in an error message.
        assertThat(row.value(OrderImportColumn.QUANTITY)).isEqualTo("12");
    }

    @Test
    @DisplayName("a decimal numeric cell keeps its value")
    void decimalCells() {
        byte[] workbook = workbook(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("externalReference");
            header.createCell(1).setCellValue("originCode");
            header.createCell(2).setCellValue("destinationCode");
            header.createCell(3).setCellValue("serviceDate");
            header.createCell(4).setCellValue("unitWeightKg");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ORD-1");
            row.createCell(1).setCellValue("LIM-01");
            row.createCell(2).setCellValue("STORE-1");
            row.createCell(3).setCellValue("2026-03-01");
            row.createCell(4).setCellValue(12.5d);
        });

        assertThat(PARSER.parse(workbook, OrderImportFormat.XLSX).rows().get(0)
                .value(OrderImportColumn.UNIT_WEIGHT_KG)).isEqualTo("12.5");
    }

    private static CellStyle dateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        return style;
    }

    private static byte[] workbook(Consumer<Sheet> content) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            content.accept(workbook.createSheet("Orders"));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Test
    @DisplayName("the two formats produce identical rows for the same data")
    void formatsAgree() {
        List<OrderImportRow> fromCsv =
                parseCsv(HEADER + "\nORD-1,LIM-01,STORE-1,2026-03-01,SKU-1,12,EA\n").rows();

        byte[] workbook = workbook(sheet -> {
            Row header = sheet.createRow(0);
            String[] headers = HEADER.split(",");
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            Row row = sheet.createRow(1);
            String[] values = {"ORD-1", "LIM-01", "STORE-1", "2026-03-01", "SKU-1", "12", "EA"};
            for (int index = 0; index < values.length; index++) {
                row.createCell(index).setCellValue(values[index]);
            }
        });

        assertThat(PARSER.parse(workbook, OrderImportFormat.XLSX).rows()).isEqualTo(fromCsv);
    }
}
