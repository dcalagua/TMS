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
        document.put("lines", invoice.lines().stream().map(JsonPayableExportAdapter::line).toList());

        return new PayableDocument(referenceFor(invoice), PayableExport.Format.JSON,
                objectMapper.writeValueAsString(document));
    }

    private static Map<String, Object> line(PayableLine line) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("lineNumber", line.lineNumber());
        rendered.put("tripId", line.tripId() == null ? null : line.tripId().toString());
        rendered.put("description", line.description());
        rendered.put("amount", line.amount().toPlainString());
        return rendered;
    }

    /**
     * Derived from the invoice, so two exports of one invoice carry one reference.
     *
     * <p>The invoice id is already unique per installation and is what the obligation is about.
     * Prefixed so a human reading an accounting system can tell where it came from.
     */
    private static String referenceFor(PayableInvoice invoice) {
        return "TMS-" + invoice.invoiceId();
    }
}
