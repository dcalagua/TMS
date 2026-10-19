package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.AccrualEntryType;
import com.ebim.tms.settlement.domain.AccrualSourceType;
import com.ebim.tms.settlement.domain.GlAccountMapping;
import com.ebim.tms.settlement.domain.GlConcept;
import com.ebim.tms.settlement.domain.GlScopeType;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accrual journal against a golden file (V65, ADR-023). The file is the contract an ERP import
 * is written against: both sides of every movement, the most specific mapping, empty - never
 * invented - accounts where none applies, RFC 4180 quoting, CRLF, no BOM.
 */
class AccrualJournalTest {

    private static final UUID COMPANY = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID T1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID T2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INVOICE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ROUTE_1 = UUID.fromString("55555555-5555-4555-8555-555555555551");
    private static final UUID ORIGIN_1 = UUID.fromString("66666666-6666-4666-8666-666666666661");
    private static final UUID ORIGIN_2 = UUID.fromString("66666666-6666-4666-8666-666666666662");

    @Test
    @DisplayName("matches the golden file byte for byte")
    void goldenFile() throws IOException {
        assertThat(AccrualJournal.csv(journal())).isEqualTo(golden());
    }

    @Test
    @DisplayName("each concept without a mapping is one warning with how many sides it leaves empty")
    void warnings() {
        assertThat(journal().warnings()).containsExactly(
                "No active GL mapping for FREIGHT_ACCRUAL: 4 movement side(s) leave that account empty.",
                "No active GL mapping for CARRIER_PAYABLE: 1 movement side(s) leave that account empty.");
    }

    @Test
    @DisplayName("with every concept mapped there is no warning")
    void noWarnings() {
        AccrualJournalView view = AccrualJournal.build(YearMonth.of(2026, 10), movements(), List.of(
                mapping(GlConcept.FREIGHT_EXPENSE, GlScopeType.COMPANY, null, "6311", null),
                mapping(GlConcept.FREIGHT_ACCRUAL, GlScopeType.COMPANY, null, "4699", null),
                mapping(GlConcept.CARRIER_PAYABLE, GlScopeType.COMPANY, null, "4212", null)));
        assertThat(view.warnings()).isEmpty();
        assertThat(view.rows()).allSatisfy(row -> {
            assertThat(row.debitAccount()).isNotNull();
            assertThat(row.creditAccount()).isNotNull();
        });
    }

    @Test
    @DisplayName("amounts are positive; the sign lives in which side is debited")
    void sides() {
        List<AccrualJournalView.Row> rows = journal().rows();
        assertThat(rows).extracting(AccrualJournalView.Row::amount).allMatch(amount -> amount.signum() > 0);
        assertThat(rows.get(4).debitConcept()).isEqualTo("FREIGHT_ACCRUAL");
        assertThat(rows.get(4).creditConcept()).isEqualTo("FREIGHT_EXPENSE");
    }

    private static AccrualJournalView journal() {
        GlAccountMapping retired = mapping(GlConcept.CARRIER_PAYABLE, GlScopeType.COMPANY, null, "4212", null);
        retired.deactivate(null);
        return AccrualJournal.build(YearMonth.of(2026, 10), movements(), List.of(
                mapping(GlConcept.FREIGHT_EXPENSE, GlScopeType.COMPANY, null, "6311", "CC-01"),
                mapping(GlConcept.FREIGHT_EXPENSE, GlScopeType.ROUTE, ROUTE_1, "6312", null),
                mapping(GlConcept.FREIGHT_ACCRUAL, GlScopeType.ORIGIN, ORIGIN_2, "4699", null),
                retired));
    }

    private static List<AccrualJournal.Movement> movements() {
        return List.of(
                t1(1, "2026-10-03", AccrualEntryType.ACCRUE, AccrualSourceType.TRIP_DISPATCHED, T1.toString(),
                        "1450.00", null, null),
                t1(2, "2026-10-10", AccrualEntryType.ADJUST, AccrualSourceType.TRIP_COST_ACTUAL,
                        "2026-10-10T15:00:00Z", "50.00", null, null),
                t1(3, "2026-10-12", AccrualEntryType.CONSUME, AccrualSourceType.INVOICE_APPROVED, INVOICE.toString(),
                        "-1000.00", null, "F001-123"),
                t1(4, "2026-10-20", AccrualEntryType.REVERSE, AccrualSourceType.MANUAL,
                        "44444444-4444-4444-8444-444444444444", "-500.00", "Not billable, \"quoted\" by the carrier",
                        null),
                new AccrualJournal.Movement(entry(5), LocalDate.parse("2026-10-21"), AccrualEntryType.ADJUST,
                        AccrualSourceType.TRIP_COST_ACTUAL, "2026-10-21T09:30:00Z", new BigDecimal("-20.00"), null,
                        T2, "SH-0002", null, ORIGIN_2, "CARR-B", "Trans, Lima SAC", null, "USD"));
    }

    private static AccrualJournal.Movement t1(int n, String date, AccrualEntryType type, AccrualSourceType source,
            String key, String amount, String reason, String invoiceNumber) {
        return new AccrualJournal.Movement(entry(n), LocalDate.parse(date), type, source, key, new BigDecimal(amount),
                reason, T1, "SH-0001", ROUTE_1, ORIGIN_1, "CARR-A", "Carrier A SA", invoiceNumber, "PEN");
    }

    private static UUID entry(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    private static GlAccountMapping mapping(GlConcept concept, GlScopeType scope, UUID scopeId, String account,
            String costCenter) {
        return new GlAccountMapping(COMPANY, concept, scope, scopeId, account, costCenter, null);
    }

    private static String golden() throws IOException {
        try (InputStream in = AccrualJournalTest.class.getResourceAsStream("/settlement/accrual-journal-golden.csv")) {
            assertThat(in).as("golden file on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
