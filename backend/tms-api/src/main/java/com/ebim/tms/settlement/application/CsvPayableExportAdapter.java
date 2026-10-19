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
 * columns and the company-level GL accounts to both, together; ADR-027 made them country-neutral.
 */
@Component
class CsvPayableExportAdapter implements PayableExportPort {

    /** In write order. Named after the JSON fields, never translated - see {@code KpiService}. */
    static final List<String> HEADER = List.of(
            "reference", "invoiceId", "invoiceNumber", "invoiceDate", "carrierId", "carrierName",
            "currency", "totalAmount", "expectedBasis",
            // V66 (ADR-024 part 2, item 10; country-neutral since ADR-027).
            "fiscalCountry", "documentKind", "fiscalDocumentCode", "effectiveSign", "documentNumber", "issuerTaxId",
            "subtotalAmount", "taxRate", "taxAmount", "withholdings", "withholdingTotalAmount",
            "referencedInvoiceId", "referencedInvoiceNumber", "netAmount",
            "glFreightExpense", "glVatCredit", "glWithholdingPayable", "glCarrierPayable",
            "lineNumber", "tripId", "chargeCode", "description", "lineAmount", "penaltyReason");

    /**
     * How the withholdings - a list, one per kind - fit one cell (ADR-027): entries separated by
     * {@code ;}, fields by {@code |}, in this order. Empty when the document carries none. A list in a
     * cell rather than one column group per kind, because the kinds are the regime's and grow with
     * every country; the JSON export carries the same as an array.
     */
    static final List<String> WITHHOLDING_FIELDS = List.of("kind", "code", "base", "baseAmount", "rate", "amount",
            "certificateNumber", "certificateDate", "glAccount");

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
            row.add(fiscal.fiscalCountry());
            row.add(fiscal.documentKind());
            row.add(fiscal.fiscalDocumentCode());
            row.add(Integer.toString(fiscal.effectiveSign()));
            row.add(fiscal.documentNumber());
            row.add(fiscal.issuerTaxId());
            row.add(plain(fiscal.subtotalAmount()));
            row.add(plain(fiscal.taxRate()));
            row.add(plain(fiscal.taxAmount()));
            row.add(withholdings(fiscal.withholdings()));
            row.add(plain(fiscal.withholdingTotalAmount()));
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

    private static String withholdings(List<WithholdingDetail> withholdings) {
        if (withholdings.isEmpty()) {
            return null;
        }
        return withholdings.stream()
                .map(withholding -> String.join("|",
                        orEmpty(withholding.kind()), orEmpty(withholding.code()), orEmpty(withholding.base()),
                        orEmpty(plain(withholding.baseAmount())), orEmpty(plain(withholding.rate())),
                        orEmpty(plain(withholding.amount())), orEmpty(withholding.certificateNumber()),
                        withholding.certificateDate() == null ? "" : withholding.certificateDate().toString(),
                        orEmpty(withholding.glAccount())))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
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
