package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.settlement.domain.FreightMatcher.InvoiceLine;
import com.ebim.tms.settlement.domain.FreightMatcher.TripCostSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Credit and debit notes against the invoice they correct (V66, ADR-024 part 2, item 5). */
class NoteMatcherTest {

    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID OTHER_TRIP = UUID.randomUUID();

    /** Invoice F001-1 billed 1000 for TRIP and nothing has corrected it yet. */
    private static final NoteMatcher.ReferencedInvoice INVOICE = new NoteMatcher.ReferencedInvoice("F001-1", "PEN",
            new BigDecimal("1000.00"), Map.of(TRIP, new BigDecimal("1000.00")));

    private static final Map<UUID, TripCostSnapshot> COST_1050 =
            Map.of(TRIP, new TripCostSnapshot(TRIP, new BigDecimal("1050.00"), null, "PEN"));

    private static InvoiceLine line(UUID trip, String amount, ChargeCode code) {
        return new InvoiceLine(UUID.randomUUID(), trip, new BigDecimal(amount), "Line", code);
    }

    @Test
    @DisplayName("a credit note within what remains of the invoice matches; the expected figure is that remainder")
    void creditWithinRemainder() {
        FreightMatchResult result = NoteMatcher.match(DocumentType.CREDIT_NOTE, "PEN", new BigDecimal("100.00"),
                List.of(line(TRIP, "100.00", ChargeCode.FREIGHT)), INVOICE, COST_1050, Tolerance.NONE);

        assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
        assertThat(result.expectedAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.invoicedAmount()).isEqualByComparingTo("100.00");
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("a credit note taking back more than remains is a TOTAL_AMOUNT discrepancy")
    void creditAboveRemainder() {
        NoteMatcher.ReferencedInvoice alreadyCredited = new NoteMatcher.ReferencedInvoice("F001-1", "PEN",
                new BigDecimal("150.00"), Map.of(TRIP, new BigDecimal("1000.00")));
        FreightMatchResult result = NoteMatcher.match(DocumentType.CREDIT_NOTE, "PEN", new BigDecimal("200.00"),
                List.of(line(TRIP, "200.00", ChargeCode.FREIGHT)), alreadyCredited, COST_1050, Tolerance.NONE);

        assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(result.discrepancies()).singleElement().satisfies(found -> {
            assertThat(found.type()).isEqualTo(DiscrepancyType.TOTAL_AMOUNT);
            assertThat(found.expectedAmount()).isEqualByComparingTo("150.00");
            assertThat(found.differenceAmount()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    @DisplayName("a note line naming a shipment the invoice does not bill is UNMATCHED_TRIP")
    void foreignShipment() {
        FreightMatchResult result = NoteMatcher.match(DocumentType.CREDIT_NOTE, "PEN", new BigDecimal("50.00"),
                List.of(line(OTHER_TRIP, "50.00", ChargeCode.FREIGHT)), INVOICE, COST_1050, Tolerance.NONE);

        assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                .containsExactly(DiscrepancyType.UNMATCHED_TRIP);
        assertThat(result.unmatchedLineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a debit note adds to what the shipment was billed: within the expected figure it matches")
    void debitWithinExpected() {
        FreightMatchResult result = NoteMatcher.match(DocumentType.DEBIT_NOTE, "PEN", new BigDecimal("50.00"),
                List.of(line(TRIP, "50.00", ChargeCode.WAITING_TIME)), INVOICE, COST_1050, Tolerance.NONE);

        assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
        assertThat(result.expectedAmount()).isEqualByComparingTo("50.00"); // 1050 - 1000 still to bill
        assertThat(result.differenceAmount()).isEqualByComparingTo("0");
        assertThat(result.expectedBasis()).isEqualTo(ExpectedBasis.ESTIMATED);
    }

    @Test
    @DisplayName("a debit note that takes the shipment over its figure is a LINE_AMOUNT discrepancy")
    void debitAboveExpected() {
        FreightMatchResult result = NoteMatcher.match(DocumentType.DEBIT_NOTE, "PEN", new BigDecimal("120.00"),
                List.of(line(TRIP, "120.00", ChargeCode.WAITING_TIME)), INVOICE, COST_1050, Tolerance.NONE);

        assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(result.discrepancies()).singleElement().satisfies(found -> {
            assertThat(found.type()).isEqualTo(DiscrepancyType.LINE_AMOUNT);
            assertThat(found.expectedAmount()).isEqualByComparingTo("1050.00");
            assertThat(found.invoicedAmount()).isEqualByComparingTo("1120.00");
            assertThat(found.differenceAmount()).isEqualByComparingTo("70.00");
        });
    }

    @Test
    @DisplayName("a debit note on a shipment nobody priced is UNMATCHABLE, never compared with zero")
    void debitUnpriced() {
        FreightMatchResult result = NoteMatcher.match(DocumentType.DEBIT_NOTE, "PEN", new BigDecimal("50.00"),
                List.of(line(TRIP, "50.00", ChargeCode.FREIGHT)), INVOICE,
                Map.of(TRIP, new TripCostSnapshot(TRIP, null, null, null)), Tolerance.NONE);

        assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHABLE);
        assertThat(result.expectedAmount()).isNull();
        assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                .containsExactly(DiscrepancyType.MISSING_EXPECTED_COST);
    }

    @Test
    @DisplayName("a note in another currency than its invoice says so; a penalty on a note is listed to accept")
    void currencyAndPenalty() {
        FreightMatchResult result = NoteMatcher.match(DocumentType.CREDIT_NOTE, "USD", new BigDecimal("10.00"),
                List.of(line(TRIP, "-10.00", ChargeCode.PENALTY)), INVOICE, COST_1050, Tolerance.NONE);

        assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                .containsExactly(DiscrepancyType.CURRENCY_MISMATCH, DiscrepancyType.PENALTY);
    }

    @Test
    @DisplayName("an invoice is not a note")
    void invoiceRefused() {
        assertThatThrownBy(() -> NoteMatcher.match(DocumentType.INVOICE, "PEN", BigDecimal.ONE, List.of(), INVOICE,
                Map.of(), Tolerance.NONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the sign comes from the type: amounts are stored positive")
    void sign() {
        assertThat(DocumentType.CREDIT_NOTE.effectiveSign()).isEqualTo(-1);
        assertThat(DocumentType.DEBIT_NOTE.effectiveSign()).isEqualTo(1);
        assertThat(DocumentType.INVOICE.effectiveSign()).isEqualTo(1);
        assertThat(DocumentType.ofCode("07")).isEqualTo(DocumentType.CREDIT_NOTE);
        assertThat(new DocumentType.CodeConverter().convertToDatabaseColumn(DocumentType.DEBIT_NOTE)).isEqualTo("08");
    }
}
