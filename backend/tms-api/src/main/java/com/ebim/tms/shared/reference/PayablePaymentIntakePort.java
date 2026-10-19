package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where the inbound API hands a payment the ERP reports (V67, ADR-023 section 2,
 * {@code POST /integration/v1/payable-payments}). Implemented by {@code settlement}, which owns the
 * rules: the invoice must have been exported, the currency must be the invoice's, and the amount may
 * not exceed the open balance. TMS records the payment and moves no money (D-01).
 *
 * <p>Opens its own transaction, like the other intake ports, so a refusal leaves nothing behind.
 */
public interface PayablePaymentIntakePort {

    /**
     * How the ERP names the invoice: TMS's own id (the export carries it), or the fiscal document
     * (issuer tax id, document type - the country's code or the kind's name - and fiscal number, in the
     * format of the company's regime), or the carrier's code and the printed number for a document
     * without a fiscal identity. Exactly one of the three.
     */
    record InvoiceKey(UUID invoiceId, String issuerTaxId, String documentType, String documentNumber,
            String carrierCode, String invoiceNumber) {
    }

    /**
     * One withholding as the ERP reports it with the payment (ADR-027): informative, and the source of
     * its certificate.
     *
     * @param kind the withholding kind's name ({@code PE_DETRACTION}, {@code EC_INCOME_TAX_WITHHOLDING}...)
     */
    record Withholding(String kind, BigDecimal amount, String certificateNumber, LocalDate certificateDate) {
    }

    record Command(UUID integrationClientId, InvoiceKey invoice, String paymentReference, LocalDate paidOn,
            BigDecimal amount, String currency, java.util.List<Withholding> withholdings) {
    }

    /** {@code RECORDED} the first time a reference is reported; {@code DUPLICATE} when it repeats unchanged. */
    enum Outcome { RECORDED, DUPLICATE }

    record Result(UUID invoiceId, String invoiceNumber, UUID paymentId, Outcome outcome, String status,
            BigDecimal paidAmount, BigDecimal openBalance, LocalDate dueDate) {
    }

    Result record(CompanyScope scope, Command command);
}
