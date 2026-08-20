package com.ebim.tms.orders.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.TotalsSource;
import com.ebim.tms.orders.domain.OrderTotals;
import com.ebim.tms.shared.reference.MasterReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The import's business rules, with no Spring context and no database: {@link
 * OrderImportValidator} is deliberately a pure function over parsed rows and a resolved snapshot
 * of the caller's company, so grouping, master resolution, the totals precedence rule, the
 * idempotency decision and the cross-tenant guarantee are all provable on a machine where Docker
 * is unavailable.
 *
 * <p>{@code OrderImportApiIntegrationTest} proves the same rules again over real HTTP and a real
 * database, but only where Docker allows it - these are the ones that always run.
 */
class OrderImportValidatorTest {

    private static final MasterReference ORIGIN =
            new MasterReference(UUID.randomUUID(), "LIM-01", "Lima distribution centre");
    private static final MasterReference DESTINATION =
            new MasterReference(UUID.randomUUID(), "STORE-1", "Miraflores store");

    private static final String HEADER = "externalReference,originCode,destinationCode,serviceDate,priority,"
            + "windowStart,windowEnd,declaredWeightKg,declaredVolumeM3,declaredPallets,"
            + "materialCode,materialDescription,quantity,uom,unitWeightKg,unitVolumeM3,palletQuantity";

    /** The company contains one origin and one destination, and holds no external reference yet. */
    private static OrderImportValidator.MasterSnapshot company() {
        return company(Set.of());
    }

    private static OrderImportValidator.MasterSnapshot company(Set<String> existingReferences) {
        return new OrderImportValidator.MasterSnapshot(
                Map.of(ORIGIN.code(), ORIGIN), Map.of(DESTINATION.code(), DESTINATION), existingReferences);
    }

    private static OrderImportValidator.Result validate(String csvBody) {
        return validate(csvBody, company());
    }

    private static OrderImportValidator.Result validate(String csvBody, OrderImportValidator.MasterSnapshot snapshot) {
        var parsed = new OrderImportParser().parse(
                (HEADER + "\n" + csvBody).getBytes(StandardCharsets.UTF_8), OrderImportFormat.CSV);
        return OrderImportValidator.validate(parsed.rows(), snapshot);
    }

    /** externalReference,origin,destination,date,priority,ws,we,dW,dV,dP,mat,desc,qty,uom,uW,uV,pal */
    private static String row(String reference, String materialCode, String quantity, String unitWeight) {
        return reference + ",LIM-01,STORE-1,2026-03-01,NORMAL,,,,,," + materialCode + ","
                + materialCode + " description," + quantity + ",EA," + unitWeight + ",,\n";
    }

    // --- grouping ---------------------------------------------------------------------

    @Test
    @DisplayName("rows sharing an external reference become one order with several lines")
    void rowsGroupIntoOneOrder() {
        OrderImportValidator.Result result = validate(row("ORD-1", "SKU-1", "2", "10") + row("ORD-1", "SKU-2", "3", "4"));

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates()).hasSize(1);
        OrderImportCandidate candidate = result.candidates().get(0);
        assertThat(candidate.lines()).hasSize(2);
        assertThat(candidate.outcome()).isEqualTo(OrderImportReport.Outcome.CREATE);
        assertThat(candidate.origin()).isEqualTo(ORIGIN);
        assertThat(candidate.destination()).isEqualTo(DESTINATION);
        assertThat(candidate.firstRowNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("rows of one order need not be adjacent")
    void nonAdjacentRowsStillGroup() {
        OrderImportValidator.Result result = validate(
                row("ORD-1", "SKU-1", "2", "10") + row("ORD-2", "SKU-9", "1", "1") + row("ORD-1", "SKU-2", "3", "4"));

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates()).extracting(OrderImportCandidate::externalReference)
                .containsExactly("ORD-1", "ORD-2");
        assertThat(result.candidates().get(0).lines()).hasSize(2);
    }

    @Test
    @DisplayName("a row with no external reference cannot be attached to an order")
    void rowWithoutReference() {
        OrderImportValidator.Result result = validate(row("", "SKU-1", "2", "10"));

        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.column()).isEqualTo("externalReference"));
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("a second row that contradicts its order's header is reported, never silently ignored")
    void contradictingHeaderRow() {
        String rows = row("ORD-1", "SKU-1", "2", "10")
                + "ORD-1,LIM-01,STORE-1,2026-09-09,NORMAL,,,,,,SKU-2,SKU-2 description,3,EA,4,,\n";

        OrderImportValidator.Result result = validate(rows);

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.column()).isEqualTo("serviceDate");
            assertThat(issue.rowNumber()).isEqualTo(3);
            assertThat(issue.message()).contains("2026-09-09").contains("row 2");
        });
        assertThat(result.candidates()).singleElement()
                .extracting(OrderImportCandidate::outcome).isEqualTo(OrderImportReport.Outcome.REJECTED);
    }

    @Test
    @DisplayName("a continuation row that leaves the header columns blank is not a contradiction")
    void blankContinuationRowIsFine() {
        String rows = row("ORD-1", "SKU-1", "2", "10")
                + "ORD-1,,,,,,,,,,SKU-2,SKU-2 description,3,EA,4,,\n";

        assertThat(validate(rows).issues()).isEmpty();
    }

    // --- master resolution and the cross-tenant guarantee --------------------------------

    @Test
    @DisplayName("an origin code the company does not have is reported against its own cell")
    void unknownOrigin() {
        String rows = "ORD-1,NOPE-99,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        OrderImportValidator.Result result = validate(rows);

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.column()).isEqualTo("originCode");
            assertThat(issue.message()).contains("NOPE-99").contains("active origin in this company");
        });
        assertThat(result.candidates().get(0).outcome()).isEqualTo(OrderImportReport.Outcome.REJECTED);
    }

    @Test
    @DisplayName("another company's origin code is refused, and is indistinguishable from a typo")
    void crossTenantOriginIsRefusedWithoutConfirmingItExists() {
        // The snapshot is built from company-scoped lookups, so another tenant's code is simply
        // absent from it - and the message must not hint that the code exists elsewhere, or the
        // import becomes an oracle for another tenant's master data.
        String rows = "ORD-1,OTHER-CO-01,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        OrderImportValidator.Result result = validate(rows);

        assertThat(result.issues()).singleElement().satisfies(issue ->
                assertThat(issue.message())
                        .isEqualTo("'OTHER-CO-01' does not match an active origin in this company."));
        assertThat(result.candidates().get(0).origin()).isNull();
    }

    @Test
    @DisplayName("a code resolves regardless of the casing a spreadsheet happens to use")
    void codeMatchingIsCaseInsensitive() {
        String rows = "ORD-1,lim-01,Store-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        OrderImportValidator.Result result = validate(rows);

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).origin()).isEqualTo(ORIGIN);
    }

    @Test
    @DisplayName("a missing destination code is reported as required")
    void missingDestination() {
        String rows = "ORD-1,LIM-01,,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        assertThat(validate(rows).issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).isEqualTo("A destination code is required."));
    }

    // --- idempotency ---------------------------------------------------------------------

    @Test
    @DisplayName("an external reference the company already holds is skipped, not duplicated and not an error")
    void duplicateReferenceIsSkipped() {
        OrderImportValidator.Result result =
                validate(row("ORD-1", "SKU-1", "2", "10"), company(Set.of("ORD-1")));

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates()).singleElement()
                .extracting(OrderImportCandidate::outcome).isEqualTo(OrderImportReport.Outcome.SKIPPED_DUPLICATE);
    }

    @Test
    @DisplayName("re-importing a file mixes skipped and new orders without either affecting the other")
    void partiallyAlreadyImportedFile() {
        OrderImportValidator.Result result = validate(
                row("ORD-1", "SKU-1", "2", "10") + row("ORD-2", "SKU-2", "1", "5"), company(Set.of("ORD-1")));

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates()).extracting(OrderImportCandidate::outcome)
                .containsExactly(OrderImportReport.Outcome.SKIPPED_DUPLICATE, OrderImportReport.Outcome.CREATE);
    }

    @Test
    @DisplayName("an already-present reference whose row is also invalid is rejected, not skipped")
    void invalidBeatsDuplicate() {
        // Rejected outranks skipped on purpose: reporting "already imported" would tell the
        // operator their broken row was fine.
        String rows = "ORD-1,NOPE,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        assertThat(validate(rows, company(Set.of("ORD-1"))).candidates())
                .singleElement().extracting(OrderImportCandidate::outcome)
                .isEqualTo(OrderImportReport.Outcome.REJECTED);
    }

    // --- totals ----------------------------------------------------------------------------

    @Test
    @DisplayName("an order with lines takes its totals from them")
    void totalsFromLines() {
        OrderImportCandidate candidate =
                validate(row("ORD-1", "SKU-1", "2", "10")).candidates().get(0);

        OrderTotals totals = OrderTotals.resolve(candidate.lines(), candidate.declaredTotals());
        assertThat(totals.source()).isEqualTo(TotalsSource.CALCULATED);
        assertThat(totals.weightKg()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("a row with no material columns is a header-only order carrying declared totals")
    void headerOnlyOrderWithDeclaredTotals() {
        String rows = "ORD-2,LIM-01,STORE-1,2026-03-01,HIGH,,,1200,3.4,2,,,,,,,\n";

        OrderImportValidator.Result result = validate(rows);

        assertThat(result.issues()).isEmpty();
        OrderImportCandidate candidate = result.candidates().get(0);
        assertThat(candidate.lines()).isEmpty();
        assertThat(candidate.priority()).isEqualTo(OrderPriority.HIGH);

        OrderTotals totals = OrderTotals.resolve(candidate.lines(), candidate.declaredTotals());
        assertThat(totals.source()).isEqualTo(TotalsSource.DECLARED);
        assertThat(totals.weightKg()).isEqualByComparingTo("1200");
        assertThat(totals.pallets()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a declared total that contradicts the lines is reported against the declared cell")
    void declaredContradictingLines() {
        // Lines add to 20 kg, the file declares 1,200 - the per-unit/per-case mistake.
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,1200,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        OrderImportValidator.Result result = validate(rows);

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.column()).isEqualTo("declaredWeightKg");
            assertThat(issue.message()).contains("1200").contains("20");
        });
        assertThat(result.candidates().get(0).outcome()).isEqualTo(OrderImportReport.Outcome.REJECTED);
    }

    @Test
    @DisplayName("a declared total that agrees with the lines within tolerance is accepted")
    void declaredAgreeingWithLines() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,20,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        assertThat(validate(rows).issues()).isEmpty();
    }

    // --- cell-level validation ---------------------------------------------------------------

    @Test
    @DisplayName("an unparseable quantity names the row, the column and what was wrong")
    void badQuantity() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,twelve,EA,,,\n";

        assertThat(validate(rows).issues()).singleElement().satisfies(issue -> {
            assertThat(issue.rowNumber()).isEqualTo(2);
            assertThat(issue.column()).isEqualTo("quantity");
            assertThat(issue.externalReference()).isEqualTo("ORD-1");
            assertThat(issue.message()).isEqualTo("'twelve' is not a number.");
        });
    }

    @Test
    @DisplayName("a zero or negative quantity is refused")
    void nonPositiveQuantity() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,0,EA,,,\n";

        assertThat(validate(rows).issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("must be greater than zero"));
    }

    @Test
    @DisplayName("a line missing its unit of measure is refused")
    void lineWithoutUom() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,2,,,,\n";

        assertThat(validate(rows).issues()).singleElement()
                .satisfies(issue -> assertThat(issue.column()).isEqualTo("uom"));
    }

    @Test
    @DisplayName("a row that fills some line columns but has no material code is an incomplete line")
    void incompleteLine() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,,,,,,2,EA,,,\n";

        assertThat(validate(rows).issues()).singleElement().satisfies(issue -> {
            assertThat(issue.column()).isEqualTo("materialCode");
            assertThat(issue.message()).contains("no material code");
        });
    }

    @Test
    @DisplayName("an unrecognised priority lists the accepted values")
    void badPriority() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,SOMEDAY,,,,,,SKU-1,SKU-1 description,2,EA,,,\n";

        assertThat(validate(rows).issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("LOW, NORMAL, HIGH, URGENT"));
    }

    @Test
    @DisplayName("a blank priority defaults to NORMAL rather than failing")
    void blankPriorityDefaults() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,,,,,,,SKU-1,SKU-1 description,2,EA,,,\n";

        OrderImportValidator.Result result = validate(rows);
        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).priority()).isEqualTo(OrderPriority.NORMAL);
    }

    @Test
    @DisplayName("half a time window is refused")
    void halfAWindow() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,08:00,,,,,SKU-1,SKU-1 description,2,EA,,,\n";

        assertThat(validate(rows).issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("both a start and an end"));
    }

    @Test
    @DisplayName("a window that ends before it starts is refused")
    void invertedWindow() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,18:00,08:00,,,,SKU-1,SKU-1 description,2,EA,,,\n";

        assertThat(validate(rows).issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("later than the window start"));
    }

    @Test
    @DisplayName("a full window is accepted and carried onto the candidate")
    void validWindow() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,08:00,12:00,,,,SKU-1,SKU-1 description,2,EA,,,\n";

        OrderImportValidator.Result result = validate(rows);
        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).windowStart()).hasToString("08:00");
        assertThat(result.candidates().get(0).windowEnd()).hasToString("12:00");
    }

    @Test
    @DisplayName("a day-first date is read day first, as the message promises")
    void dayFirstDate() {
        String rows = "ORD-1,LIM-01,STORE-1,03/04/2026,NORMAL,,,,,,SKU-1,SKU-1 description,2,EA,,,\n";

        assertThat(validate(rows).candidates().get(0).serviceDate()).hasToString("2026-04-03");
    }

    @Test
    @DisplayName("a line description defaults to its material code rather than being required")
    void descriptionDefaultsToCode() {
        String rows = "ORD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,,2,EA,,,\n";

        OrderImportValidator.Result result = validate(rows);
        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).lines().get(0).materialDescription()).isEqualTo("SKU-1");
    }

    // --- limits ----------------------------------------------------------------------------

    @Test
    @DisplayName("a file describing more orders than the limit is refused as a whole")
    void tooManyOrders() {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index <= OrderImportLimits.MAX_ORDERS; index++) {
            rows.append(row("ORD-" + index, "SKU-1", "1", "1"));
        }

        OrderImportValidator.Result result = validate(rows.toString());

        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message())
                        .contains("more than the limit of " + OrderImportLimits.MAX_ORDERS));
    }

    @Test
    @DisplayName("an order with more lines than the limit is reported rather than silently truncated")
    void tooManyLines() {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index <= OrderImportLimits.MAX_LINES_PER_ORDER; index++) {
            rows.append(row("ORD-1", "SKU-" + index, "1", "1"));
        }

        OrderImportValidator.Result result = validate(rows.toString());

        assertThat(result.issues()).isNotEmpty();
        assertThat(result.issues()).anySatisfy(issue -> assertThat(issue.message()).contains("more than "
                + OrderImportLimits.MAX_LINES_PER_ORDER + " lines"));
        assertThat(result.candidates()).singleElement()
                .extracting(OrderImportCandidate::outcome).isEqualTo(OrderImportReport.Outcome.REJECTED);
    }

    // --- the report's row numbers ------------------------------------------------------------

    @Test
    @DisplayName("a blank line in the middle of the file does not shift the row numbers below it")
    void blankLineDoesNotRenumber() {
        String rows = row("ORD-1", "SKU-1", "2", "10") + "\n"
                + "ORD-2,LIM-01,STORE-1,2026-03-01,NORMAL,,,,,,SKU-1,SKU-1 description,twelve,EA,,,\n";

        List<OrderImportReport.Issue> issues = validate(rows).issues();

        // Header 1, first order 2, blank 3, broken row 4 - which is what the operator sees.
        assertThat(issues).singleElement().extracting(OrderImportReport.Issue::rowNumber).isEqualTo(4);
    }
}
