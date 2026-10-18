package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.PayableExport;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The default hand-off: a JSON document describing one approved obligation.
 *
 * <p>Ships because a boundary with no implementation is a boundary nobody can test. It is
 * deliberately the simplest thing that is correct - <b>no vendor format</b>, because writing one
 * against a specific ERP needs a concrete customer requirement (ADR-007's rule, applied here).
 *
 * <p>The reference is derived from the invoice's own identity and nothing else. Not a clock, not a
 * counter, not a random: a retried export must produce the <em>same</em> reference, or a downstream
 * system cannot tell a retry from a second obligation.
 */
@Component
class JsonPayableExportAdapter implements PayableExportPort {

    private final ObjectMapper objectMapper;

    JsonPayableExportAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PayableDocument render(PayableInvoice invoice) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("reference", referenceFor(invoice));
        document.put("invoiceId", invoice.invoiceId().toString());
        document.put("invoiceNumber", invoice.invoiceNumber());
        document.put("invoiceDate", invoice.invoiceDate().toString());
        document.put("carrierId", invoice.carrierId().toString());
        document.put("carrierName", invoice.carrierName());
        document.put("currency", invoice.currency());
        // toPlainString: an amount handed to accounting must never arrive in scientific notation.
        document.put("totalAmount", invoice.totalAmount().toPlainString());
        // What the approved figure was compared against (V64): ACTUAL, ESTIMATED, MIXED or null.
        document.put("expectedBasis", invoice.expectedBasis());
        fiscal(document, invoice.fiscal());
        document.put("lines", invoice.lines().stream().map(JsonPayableExportAdapter::line).toList());

        return new PayableDocument(referenceFor(invoice), PayableExport.Format.JSON,
                objectMapper.writeValueAsString(document));
    }

    /** The V66 fields (ADR-024 part 2, item 10): present with null when unknown, never zero. */
    private static void fiscal(Map<String, Object> document, FiscalDetail fiscal) {
        document.put("documentType", fiscal.documentType());
        document.put("effectiveSign", fiscal.effectiveSign());
        document.put("series", fiscal.series());
        document.put("correlative", fiscal.correlative());
        document.put("issuerRuc", fiscal.issuerRuc());
        document.put("subtotalAmount", plain(fiscal.subtotalAmount()));
        document.put("igvRate", plain(fiscal.igvRate()));
        document.put("igvAmount", plain(fiscal.igvAmount()));
        Map<String, Object> detraction = null;
        if (fiscal.detractionAmount() != null) {
            detraction = new LinkedHashMap<>();
            detraction.put("code", fiscal.detractionCode());
            detraction.put("rate", plain(fiscal.detractionRate()));
            detraction.put("amount", plain(fiscal.detractionAmount()));
            detraction.put("constancyNumber", fiscal.detractionConstancyNumber());
            detraction.put("constancyDate",
                    fiscal.detractionConstancyDate() == null ? null : fiscal.detractionConstancyDate().toString());
        }
        document.put("detraction", detraction);
        Map<String, Object> retention = null;
        if (fiscal.retentionAmount() != null) {
            retention = new LinkedHashMap<>();
            retention.put("rate", plain(fiscal.retentionRate()));
            retention.put("amount", plain(fiscal.retentionAmount()));
        }
        document.put("retention", retention);
        Map<String, Object> referenced = null;
        if (fiscal.referencedInvoiceId() != null) {
            referenced = new LinkedHashMap<>();
            referenced.put("invoiceId", fiscal.referencedInvoiceId().toString());
            referenced.put("invoiceNumber", fiscal.referencedInvoiceNumber());
        }
        document.put("referencedDocument", referenced);
        document.put("netAmount", plain(fiscal.netAmount()));
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (com.ebim.tms.settlement.domain.GlConcept concept : FiscalDetail.EXPORTED_CONCEPTS) {
            accounts.put(concept.name(), fiscal.glAccounts().get(concept));
        }
        document.put("glAccounts", accounts);
    }

    private static String plain(java.math.BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static Map<String, Object> line(PayableLine line) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("lineNumber", line.lineNumber());
        rendered.put("tripId", line.tripId() == null ? null : line.tripId().toString());
        rendered.put("description", line.description());
        rendered.put("chargeCode", line.chargeCode());
        rendered.put("amount", line.amount().toPlainString());
        rendered.put("penaltyReason", line.penaltyReason());
        return rendered;
    }

    /**
     * Derived from the invoice, so two exports of one invoice carry one reference.
     *
     * <p>The invoice id is already unique per installation and is what the obligation is about.
     * Prefixed so a human reading an accounting system can tell where it came from.
     */
    @Override
    public PayableExport.Format format() {
        return PayableExport.Format.JSON;
    }

    static String referenceFor(PayableInvoice invoice) {
        return "TMS-" + invoice.invoiceId();
    }
}
