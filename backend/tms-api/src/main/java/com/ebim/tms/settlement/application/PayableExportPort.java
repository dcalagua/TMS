package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.PayableExport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The boundary between TMS and whoever pays (migration V46).
 *
 * <p><b>TMS validates and exports; the ERP pays.</b> This port is where that sentence becomes
 * structure: an implementation renders an artifact, and nothing behind it can move money.
 *
 * <p>A port rather than a direct writer for the reason ADR-006 gives for evidence storage and
 * ADR-010 for routing: the first implementation is a local artifact, a customer's is an SAP or
 * Oracle document, and no caller changes when that day comes. <b>No vendor implementation ships</b>
 * - writing one against a specific ERP needs a concrete customer requirement, exactly as ADR-007
 * says for telematics.
 */
public interface PayableExportPort {

    /** Renders the obligation. Must be deterministic: the same invoice yields the same reference. */
    PayableDocument render(PayableInvoice invoice);

    /**
     * @param reference what a downstream system deduplicates on. <b>Derived from the invoice</b>,
     *                  never from a clock or a counter, so a retried export is recognisably the
     *                  same obligation rather than a second one
     */
    record PayableDocument(String reference, PayableExport.Format format, String payload) {
    }

    record PayableInvoice(
            UUID invoiceId,
            String invoiceNumber,
            LocalDate invoiceDate,
            UUID carrierId,
            String carrierName,
            String currency,
            BigDecimal totalAmount,
            List<PayableLine> lines) {
    }

    record PayableLine(int lineNumber, UUID tripId, String description, BigDecimal amount) {
    }
}
