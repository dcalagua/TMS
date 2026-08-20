package com.ebim.tms.orders.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderTotals;
import com.ebim.tms.orders.domain.TotalsSource;
import com.ebim.tms.shared.reference.MasterReference;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The template a user downloads, put straight back through the parser and validator that will
 * read their filled-in copy.
 *
 * <p>This is the test that keeps the three in step. The template is generated from
 * {@link OrderImportColumn}, so a new column reaches the header automatically - but nothing else
 * guarantees that the example rows it ships remain <em>importable</em>, and a template whose own
 * examples are rejected is worse than no template at all.
 */
class OrderImportTemplateTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 3, 1);

    private static final OrderImportTemplate TEMPLATE = new OrderImportTemplate();
    private static final OrderImportParser PARSER = new OrderImportParser();

    /**
     * The masters the template's example rows name, as the caller's company would resolve them.
     * One instance, not one per call: {@code formatsAgree} compares whole candidates, and a fresh
     * set of random master ids each time would make two identical imports look different.
     */
    private static final OrderImportValidator.MasterSnapshot COMPANY = new OrderImportValidator.MasterSnapshot(
            Map.of("ORIGIN-01", new MasterReference(UUID.randomUUID(), "ORIGIN-01", "Main origin")),
            Map.of("STORE-01", new MasterReference(UUID.randomUUID(), "STORE-01", "Store one"),
                    "STORE-02", new MasterReference(UUID.randomUUID(), "STORE-02", "Store two")),
            Set.of());

    private static OrderImportValidator.Result importTemplate(OrderImportFormat format) {
        byte[] content = TEMPLATE.build(format, SERVICE_DATE);
        return OrderImportValidator.validate(PARSER.parse(content, format).rows(), COMPANY);
    }

    @Test
    @DisplayName("the .xlsx template's own example rows import without a single issue")
    void xlsxExamplesAreValid() {
        assertThat(importTemplate(OrderImportFormat.XLSX).issues()).isEmpty();
    }

    @Test
    @DisplayName("the .csv template's own example rows import without a single issue")
    void csvExamplesAreValid() {
        assertThat(importTemplate(OrderImportFormat.CSV).issues()).isEmpty();
    }

    @Test
    @DisplayName("the examples demonstrate both totals strategies - lines, and a header-only declaration")
    void examplesCoverBothTotalsStrategies() {
        var candidates = importTemplate(OrderImportFormat.XLSX).candidates();

        assertThat(candidates).extracting(OrderImportCandidate::externalReference)
                .containsExactly("EXAMPLE-1", "EXAMPLE-2");

        OrderImportCandidate withLines = candidates.get(0);
        assertThat(withLines.lines()).hasSize(2);
        assertThat(OrderTotals.resolve(withLines.lines(), withLines.declaredTotals()).source())
                .isEqualTo(TotalsSource.CALCULATED);

        OrderImportCandidate headerOnly = candidates.get(1);
        assertThat(headerOnly.lines()).isEmpty();
        assertThat(headerOnly.priority()).isEqualTo(OrderPriority.HIGH);
        OrderTotals declared = OrderTotals.resolve(headerOnly.lines(), headerOnly.declaredTotals());
        assertThat(declared.source()).isEqualTo(TotalsSource.DECLARED);
        assertThat(declared.weightKg()).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("both formats describe the same orders")
    void formatsAgree() {
        assertThat(importTemplate(OrderImportFormat.CSV).candidates())
                .isEqualTo(importTemplate(OrderImportFormat.XLSX).candidates());
    }

    @Test
    @DisplayName("the example service date is the one asked for, so it is never accidentally in the past")
    void exampleDateIsTheGivenOne() {
        assertThat(importTemplate(OrderImportFormat.CSV).candidates())
                .allSatisfy(candidate -> assertThat(candidate.serviceDate()).isEqualTo(SERVICE_DATE));
    }

    @Test
    @DisplayName("the workbook carries the data sheet and an instructions sheet naming every column")
    void workbookHasInstructions() throws Exception {
        byte[] content = TEMPLATE.build(OrderImportFormat.XLSX, SERVICE_DATE);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Orders");

            var instructions = workbook.getSheet("Instructions");
            assertThat(instructions).isNotNull();

            StringBuilder text = new StringBuilder();
            instructions.forEach(row -> row.forEach(cell -> {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                    text.append(cell.getStringCellValue()).append('\n');
                }
            }));
            // Every column has to appear, or a column added later ships with no explanation.
            assertThat(text.toString())
                    .contains(Arrays.stream(OrderImportColumn.values()).map(OrderImportColumn::header).toList());
        }
    }

    @Test
    @DisplayName("the CSV template starts with a BOM so Excel opens it as UTF-8")
    void csvCarriesABom() {
        byte[] content = TEMPLATE.build(OrderImportFormat.CSV, SERVICE_DATE);

        // EF BB BF. Without it Excel on Windows reads the file in the machine's ANSI code page
        // and mangles every accented character in a description.
        assertThat(content[0] & 0xFF).isEqualTo(0xEF);
        assertThat(content[1] & 0xFF).isEqualTo(0xBB);
        assertThat(content[2] & 0xFF).isEqualTo(0xBF);
    }

    @Test
    @DisplayName("the file name says which format it is")
    void fileNames() {
        assertThat(TEMPLATE.fileName(OrderImportFormat.XLSX)).endsWith(".xlsx");
        assertThat(TEMPLATE.fileName(OrderImportFormat.CSV)).endsWith(".csv");
    }
}
