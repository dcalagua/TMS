package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.AccrualEntryType;
import com.ebim.tms.settlement.domain.AccrualSourceType;
import com.ebim.tms.settlement.domain.GlAccountMapping;
import com.ebim.tms.settlement.domain.GlAccountResolver;
import com.ebim.tms.settlement.domain.GlConcept;
import com.ebim.tms.shared.io.DelimitedTextWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds and renders the accrual journal (V65, ADR-023). Pure: no repository, so the golden test
 * reads exactly what the endpoint writes.
 *
 * <h2>The two sides of each movement</h2>
 *
 * <ul>
 *   <li>{@code ACCRUE}, and an {@code ADJUST} upwards: debit FREIGHT_EXPENSE, credit FREIGHT_ACCRUAL.</li>
 *   <li>{@code ADJUST} downwards and {@code REVERSE}: debit FREIGHT_ACCRUAL, credit FREIGHT_EXPENSE.</li>
 *   <li>{@code CONSUME}: debit FREIGHT_ACCRUAL, credit CARRIER_PAYABLE - the invoice takes the
 *       obligation over. Consumption above the accrual is booked the same way; the accrual's
 *       negative balance is the variance a person reads per accrual.</li>
 * </ul>
 */
public final class AccrualJournal {

    static final List<String> CSV_HEADER = List.of("period", "entryId", "entryDate", "entryType", "sourceType",
            "sourceKey", "shipmentNumber", "carrierCode", "carrierName", "invoiceNumber", "currency", "amount",
            "debitConcept", "debitAccount", "debitCostCenter", "creditConcept", "creditAccount", "creditCostCenter",
            "reason");

    private AccrualJournal() {
    }

    /** One movement and what the journal needs to know about it. */
    public record Movement(
            UUID entryId,
            LocalDate entryDate,
            AccrualEntryType entryType,
            AccrualSourceType sourceType,
            String sourceKey,
            BigDecimal signedAmount,
            String reason,
            UUID tripId,
            String shipmentNumber,
            UUID routeId,
            UUID originId,
            String carrierCode,
            String carrierName,
            String invoiceNumber,
            String currency) {
    }

    public static AccrualJournalView build(YearMonth period, List<Movement> movements, List<GlAccountMapping> mappings) {
        GlAccountResolver resolver = new GlAccountResolver(mappings);
        Map<GlConcept, Integer> unmapped = new EnumMap<>(GlConcept.class);
        List<AccrualJournalView.Row> rows = new ArrayList<>();
        for (Movement movement : movements) {
            GlConcept debit = debitOf(movement);
            GlConcept credit = creditOf(movement);
            Optional<GlAccountMapping> debitAccount = resolver.resolve(debit, movement.routeId(), movement.originId());
            Optional<GlAccountMapping> creditAccount = resolver.resolve(credit, movement.routeId(), movement.originId());
            if (debitAccount.isEmpty()) {
                unmapped.merge(debit, 1, Integer::sum);
            }
            if (creditAccount.isEmpty()) {
                unmapped.merge(credit, 1, Integer::sum);
            }
            rows.add(new AccrualJournalView.Row(movement.entryId(), movement.entryDate(), movement.entryType().name(),
                    movement.sourceType().name(), movement.sourceKey(), movement.tripId(), movement.shipmentNumber(),
                    movement.carrierCode(), movement.carrierName(), movement.invoiceNumber(), movement.currency(),
                    movement.signedAmount().abs(),
                    debit.name(), debitAccount.map(GlAccountMapping::accountCode).orElse(null),
                    debitAccount.map(GlAccountMapping::costCenterCode).orElse(null),
                    credit.name(), creditAccount.map(GlAccountMapping::accountCode).orElse(null),
                    creditAccount.map(GlAccountMapping::costCenterCode).orElse(null),
                    movement.reason()));
        }
        List<String> warnings = new ArrayList<>();
        unmapped.forEach((concept, count) -> warnings.add("No active GL mapping for " + concept + ": " + count
                + " movement side(s) leave that account empty."));
        return new AccrualJournalView(period.toString(), rows, warnings);
    }

    /** RFC 4180, CRLF, UTF-8 without BOM - the payable export's form (V64): a file for another system. */
    public static String csv(AccrualJournalView journal) {
        List<List<String>> rows = new ArrayList<>();
        for (AccrualJournalView.Row row : journal.rows()) {
            rows.add(List.of(journal.period(), row.entryId().toString(), row.entryDate().toString(), row.entryType(),
                    row.sourceType(), cell(row.sourceKey()), cell(row.shipmentNumber()), cell(row.carrierCode()),
                    cell(row.carrierName()), cell(row.invoiceNumber()), row.currency(), row.amount().toPlainString(),
                    row.debitConcept(), cell(row.debitAccount()), cell(row.debitCostCenter()), row.creditConcept(),
                    cell(row.creditAccount()), cell(row.creditCostCenter()), cell(row.reason())));
        }
        return DelimitedTextWriter.text(CSV_HEADER, rows);
    }

    static GlConcept debitOf(Movement movement) {
        return increasesAccrual(movement) ? GlConcept.FREIGHT_EXPENSE : GlConcept.FREIGHT_ACCRUAL;
    }

    static GlConcept creditOf(Movement movement) {
        if (increasesAccrual(movement)) {
            return GlConcept.FREIGHT_ACCRUAL;
        }
        return movement.entryType() == AccrualEntryType.CONSUME ? GlConcept.CARRIER_PAYABLE : GlConcept.FREIGHT_EXPENSE;
    }

    private static boolean increasesAccrual(Movement movement) {
        return movement.signedAmount().signum() > 0;
    }

    private static String cell(String value) {
        return value == null ? "" : value;
    }
}
