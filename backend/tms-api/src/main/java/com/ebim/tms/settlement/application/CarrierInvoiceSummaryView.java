package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.FreightMatch;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.MatchStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the freight audit list (migration V46).
 *
 * <p>Carries expected, invoiced and the difference together, because the question the list has to
 * answer at a glance is "which of these disagree, and by how much" - and a status alone cannot say
 * how much.
 *
 * @param expectedAmount   <b>null when nothing could be compared</b>, never zero. The screen renders
 *                         a dash; rendering 0.00 would report every unpriced shipment as a total
 *                         overcharge
 * @param differenceAmount invoiced minus expected, null exactly when expected is null
 * @param matchStatus      null until the invoice has been matched at least once
 */
public record CarrierInvoiceSummaryView(
        UUID id,
        UUID carrierId,
        String carrierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        String currency,
        BigDecimal totalAmount,
        InvoiceStatus status,
        MatchStatus matchStatus,
        BigDecimal expectedAmount,
        BigDecimal differenceAmount,
        int openDiscrepancyCount,
        /** V66 (ADR-027): INVOICE, CREDIT_NOTE or DEBIT_NOTE - so a list can offer the invoices a note may correct. */
        String documentKind,
        /** The country's code for the kind (PE 01/07/08, EC 01/04/05); null where the regime has none. */
        String fiscalDocumentCode,
        /** V67: due date (frozen at approval) and what was paid, for the queue's payment columns. */
        LocalDate dueDate,
        BigDecimal paidAmount) {

    public static CarrierInvoiceSummaryView of(CarrierInvoice invoice, FreightMatch match, String carrierName) {
        return new CarrierInvoiceSummaryView(invoice.id(), invoice.carrierId(), carrierName,
                invoice.invoiceNumber(), invoice.invoiceDate(), invoice.currency(), invoice.totalAmount(),
                invoice.status(),
                match == null ? null : match.status(),
                match == null ? null : match.expectedAmount(),
                match == null ? null : match.differenceAmount(),
                0, invoice.documentKind().name(), invoice.fiscalDocumentCode(), invoice.dueDate(),
                invoice.paidAmount());
    }
}
