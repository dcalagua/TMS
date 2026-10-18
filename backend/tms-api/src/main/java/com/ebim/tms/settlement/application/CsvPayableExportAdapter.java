package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.PayableExport;
import com.ebim.tms.shared.io.DelimitedTextWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The approved obligation as CSV (V64, ADR-024 part 1, item 6) - the format the V46 enum and
 * {@code ck_payable_export_format} admitted and nothing rendered.
 *
 * <p>RFC 4180: comma-separated, CRLF, a field quoted only when it holds a comma, a quote or a line
 * break. UTF-8 <b>without</b> a byte-order mark (this is handed to a system, not opened by a person),
 * a dot as decimal separator and never scientific notation, ISO-8601 dates. <b>One row per invoice
 * line, with the header fields repeated on each</b>: the shape every ERP import and every
 * spreadsheet pivot reads without a second file.
 *
 * <p>The same fields as {@link JsonPayableExportAdapter}, the same reference. V66 adds the fiscal
 * columns and the company-level GL accounts to both, together.
 */
@Component
class CsvPayableExportAdapter implements PayableExportPort {

    /** In write order. Named after the JSON fields, never translated - see {@code KpiService}. */
    static final List<String> HEADER = List.of(
            "reference", "invoiceId", "invoiceNumber", "invoiceDate", "carrierId", "carrierName",
            "currency", "totalAmount", "expectedBasis",
            // V66 (ADR-024 part 2, item 10).
            "documentType", "effectiveSign", "series", "correlative", "issuerRuc",
            "subtotalAmount", "igvRate", "igvAmount",
            "detractionCode", "detractionRate", "detractionAmount", "detractionConstancyNumber",
            "detractionConstancyDate", "retentionRate", "retentionAmount",
            "referencedInvoiceId", "referencedInvoiceNumber", "netAmount",
            "glFreightExpense", "glIgvCredit", "glDetraccionPayable", "glRetencionPayable", "glCarrierPayable",
            "lineNumber", "tripId", "chargeCode", "description", "lineAmount", "penaltyReason");

    @Override
    public PayableDocument render(PayableInvoice invoice) {
        String reference = JsonPayableExportAdapter.referenceFor(invoice);
        List<List<String>> rows = new ArrayList<>();
        for (PayableLine line : invoice.lines()) {
            List<String> row = new ArrayList<>(HEADER.size());
            row.add(reference);
            row.add(invoice.invoiceId().toString());
            row.add(invoice.invoiceNumber());
            row.add(invoice.invoiceDate().toString());
            row.add(invoice.carrierId().toString());
            row.add(invoice.carrierName());
            row.add(invoice.currency());
            row.add(plain(invoice.totalAmount()));
            row.add(invoice.expectedBasis());
            FiscalDetail fiscal = invoice.fiscal();
            row.add(fiscal.documentType());
            row.add(Integer.toString(fiscal.effectiveSign()));
            row.add(fiscal.series());
            row.add(fiscal.correlative());
            row.add(fiscal.issuerRuc());
            row.add(plain(fiscal.subtotalAmount()));
            row.add(plain(fiscal.igvRate()));
            row.add(plain(fiscal.igvAmount()));
            row.add(fiscal.detractionCode());
            row.add(plain(fiscal.detractionRate()));
            row.add(plain(fiscal.detractionAmount()));
            row.add(fiscal.detractionConstancyNumber());
            row.add(fiscal.detractionConstancyDate() == null ? null : fiscal.detractionConstancyDate().toString());
            row.add(plain(fiscal.retentionRate()));
            row.add(plain(fiscal.retentionAmount()));
            row.add(fiscal.referencedInvoiceId() == null ? null : fiscal.referencedInvoiceId().toString());
            row.add(fiscal.referencedInvoiceNumber());
            row.add(plain(fiscal.netAmount()));
            for (com.ebim.tms.settlement.domain.GlConcept concept : FiscalDetail.EXPORTED_CONCEPTS) {
                row.add(fiscal.glAccounts().get(concept));
            }
            row.add(Integer.toString(line.lineNumber()));
            row.add(line.tripId() == null ? null : line.tripId().toString());
            row.add(line.chargeCode());
            row.add(line.description());
            row.add(plain(line.amount()));
            row.add(line.penaltyReason());
            rows.add(row);
        }
        return new PayableDocument(reference, PayableExport.Format.CSV, DelimitedTextWriter.text(HEADER, rows));
    }

    @Override
    public PayableExport.Format format() {
        return PayableExport.Format.CSV;
    }

    /** An amount handed to accounting never arrives in scientific notation. */
    private static String plain(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
