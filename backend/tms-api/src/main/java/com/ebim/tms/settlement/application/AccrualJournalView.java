package com.ebim.tms.settlement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The accrual journal of one accounting period, for the ERP to book (V65, ADR-023). One row per
 * movement, a debit and a credit each, amounts positive. There is no general ledger here: this is a
 * file. {@code warnings} names every concept that had no GL mapping - its account is left empty,
 * never invented.
 */
public record AccrualJournalView(String period, List<Row> rows, List<String> warnings) {

    public AccrualJournalView {
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    public record Row(
            UUID entryId,
            LocalDate entryDate,
            String entryType,
            String sourceType,
            String sourceKey,
            UUID tripId,
            String shipmentNumber,
            String carrierCode,
            String carrierName,
            String invoiceNumber,
            String currency,
            BigDecimal amount,
            String debitConcept,
            String debitAccount,
            String debitCostCenter,
            String creditConcept,
            String creditAccount,
            String creditCostCenter,
            String reason) {
    }
}
