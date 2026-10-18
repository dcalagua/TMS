package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.GlConcept;
import com.ebim.tms.settlement.domain.PayableExport;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
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

    /** The one format this implementation renders; the service picks an implementation by it (V64). */
    PayableExport.Format format();

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
            String expectedBasis,
            List<PayableLine> lines,
            FiscalDetail fiscal) {

        public PayableInvoice {
            fiscal = fiscal == null ? FiscalDetail.NONE : fiscal;
        }

        /** An obligation as every caller before V66 described it: no fiscal detail. */
        public PayableInvoice(UUID invoiceId, String invoiceNumber, LocalDate invoiceDate, UUID carrierId,
                String carrierName, String currency, BigDecimal totalAmount, String expectedBasis,
                List<PayableLine> lines) {
            this(invoiceId, invoiceNumber, invoiceDate, carrierId, carrierName, currency, totalAmount, expectedBasis,
                    lines, FiscalDetail.NONE);
        }
    }

    /**
     * @param chargeCode    which charge the line bills (V64); {@code FREIGHT} for an all-in line
     * @param penaltyReason why the shipper deducts, on a {@code PENALTY} line (V66); null elsewhere
     */
    record PayableLine(int lineNumber, UUID tripId, String description, BigDecimal amount, String chargeCode,
            String penaltyReason) {

        public PayableLine(int lineNumber, UUID tripId, String description, BigDecimal amount, String chargeCode) {
            this(lineNumber, tripId, description, amount, chargeCode, null);
        }
    }

    /**
     * The fiscal part of the obligation (V66, ADR-024 part 2 item 10, ADR-027). Every field may be
     * null: a pre-V66 document has none of it, and an absent figure is never sent as zero.
     *
     * @param fiscalCountry  the regime the document was received under
     * @param effectiveSign  +1, or -1 for a credit note: what the document does to what is owed
     * @param withholdings   what the shipper keeps and deposits with the tax authority, one per kind
     * @param withholdingTotalAmount their sum; null on a document received before V66
     * @param netAmount      an invoice's total less its approved credit notes plus its approved debit
     *                       notes, at the moment of export; null on a note
     * @param glAccounts     the company-level account of each {@link #EXPORTED_CONCEPTS} concept that
     *                       has one (ADR-023); {@code WITHHOLDING_PAYABLE} is the mapping for every
     *                       kind - each withholding carries its own account. Missing = absent
     */
    record FiscalDetail(
            String fiscalCountry,
            String documentKind,
            String fiscalDocumentCode,
            int effectiveSign,
            String documentNumber,
            String issuerTaxId,
            BigDecimal subtotalAmount,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            List<WithholdingDetail> withholdings,
            BigDecimal withholdingTotalAmount,
            UUID referencedInvoiceId,
            String referencedInvoiceNumber,
            BigDecimal netAmount,
            java.util.Map<GlConcept, String> glAccounts) {

        /** The concepts a payable is booked to, in export order. */
        public static final List<GlConcept> EXPORTED_CONCEPTS = List.of(GlConcept.FREIGHT_EXPENSE,
                GlConcept.VAT_CREDIT, GlConcept.WITHHOLDING_PAYABLE, GlConcept.CARRIER_PAYABLE);

        public static final FiscalDetail NONE = new FiscalDetail(null, "INVOICE", null, 1, null, null, null, null,
                null, List.of(), null, null, null, null, java.util.Map.of());

        public FiscalDetail {
            withholdings = withholdings == null ? List.of() : List.copyOf(withholdings);
            glAccounts = glAccounts == null ? java.util.Map.of() : java.util.Map.copyOf(glAccounts);
        }

        /**
         * @param accountOf the company-level account of a concept, and of a withholding kind under
         *                  {@code WITHHOLDING_PAYABLE}; null when none is mapped
         */
        public static FiscalDetail of(CarrierInvoice invoice, CarrierInvoice referenced, BigDecimal netAmount,
                java.util.function.BiFunction<GlConcept, WithholdingKind, String> accountOf) {
            java.util.Map<GlConcept, String> accounts = new java.util.EnumMap<>(GlConcept.class);
            for (GlConcept concept : EXPORTED_CONCEPTS) {
                String account = accountOf.apply(concept, null);
                if (account != null) {
                    accounts.put(concept, account);
                }
            }
            List<WithholdingDetail> withholdings = invoice.withholdings().stream()
                    .map(withholding -> new WithholdingDetail(withholding.kind().name(), withholding.code(),
                            withholding.base().name(), withholding.baseAmount(), withholding.rate(),
                            withholding.amount(), withholding.certificateNumber(), withholding.certificateDate(),
                            accountOf.apply(GlConcept.WITHHOLDING_PAYABLE, withholding.kind())))
                    .toList();
            return new FiscalDetail(invoice.fiscalCountry(), invoice.documentKind().name(),
                    invoice.fiscalDocumentCode(), invoice.effectiveSign(), invoice.fiscalDocumentNumber(),
                    invoice.issuerTaxId(), invoice.subtotalAmount(), invoice.taxRate(), invoice.taxAmount(),
                    withholdings, invoice.fiscalCountry() == null && withholdings.isEmpty() ? null
                            : invoice.withholdingTotal(),
                    invoice.referencedInvoiceId(), referenced == null ? null : referenced.invoiceNumber(),
                    netAmount, accounts);
        }
    }

    /**
     * One withholding as it is handed to accounting (ADR-027).
     *
     * @param glAccount the account of its kind, else of every kind; null when none is mapped
     */
    record WithholdingDetail(String kind, String code, String base, BigDecimal baseAmount, BigDecimal rate,
            BigDecimal amount, String certificateNumber, LocalDate certificateDate, String glAccount) {
    }
}
