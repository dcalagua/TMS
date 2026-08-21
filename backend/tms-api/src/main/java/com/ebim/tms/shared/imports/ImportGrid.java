package com.ebim.tms.shared.imports;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.io.DelimitedTextReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads an uploaded XLSX or CSV file into a raw grid of strings - the one place besides the order
 * import that touches Apache POI (see {@code ModuleBoundaryTest.spreadsheet_library_stays_inside_the_import}).
 *
 * <p>Every entity import in the codebase (Locations, Carriers, Vehicle Types, Vehicles) reads its
 * upload through this class rather than each carrying its own copy of workbook-reading code, the
 * way {@code com.ebim.tms.orders.application.imports.OrderImportParser} originally did before this
 * package existed. What downstream code sees is always {@code List<List<String>>} of trimmed-free
 * text; column mapping is {@link ImportGridMapper}'s job, not this class's.
 */
public final class ImportGrid {

    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ImportGrid() {}

    public static List<List<String>> read(byte[] content, ImportFormat format) {
        return switch (format) {
            case XLSX -> readWorkbook(content);
            case CSV -> DelimitedTextReader.read(content);
        };
    }

    /**
     * Reads the first sheet, not a sheet found by name: the template names it, but an operator who
     * rebuilt the file in their own workbook did not, and refusing that file would be a rule about
     * naming rather than about data.
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
     * {@code toString} produces - see {@code OrderImportParser}'s original javadoc for the
     * date-serial-number and whole-number-as-double pitfalls this avoids.
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
            LocalDate date = dateTime.toLocalDate();
            LocalTime time = dateTime.toLocalTime();
            if (date.getYear() <= 1900) {
                return time.format(ISO_TIME);
            }
            return date.toString();
        }
        return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }
}
