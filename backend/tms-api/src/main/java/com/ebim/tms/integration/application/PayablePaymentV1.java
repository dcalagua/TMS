package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One payment the ERP made against an exported carrier invoice ({@code POST /integration/v1/payable-payments},
 * {@code INBOUND_API_V1.md} section 14, ADR-023 section 2, ADR-027). TMS records it; it moves no money (D-01).
 *
 * @param invoice          how the ERP names the invoice: exactly one of {@code invoiceId}, the fiscal
 *                         document, or {@code carrierCode} + {@code invoiceNumber}
 * @param paymentReference the ERP's own reference; repeating it with the same figures is a {@code DUPLICATE}
 * @param amount           paid to the carrier, in the invoice's currency, never more than the open balance
 * @param withholdings     what was withheld and deposited with the tax authority, with its certificate;
 *                         informative, and each kind must be one the invoice carries
 */
public record PayablePaymentV1(
        @NotNull @Valid InvoiceRef invoice,
        @NotBlank @Size(max = 80) String paymentReference,
        @NotNull LocalDate paidOn,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
        @Valid @Size(max = 10) List<Withholding> withholdings,
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$") String sourceSystem) {

    /**
     * @param carrierTaxId   the issuer's tax id printed on the document (Peru RUC 11 digits, Ecuador RUC 13)
     * @param documentType   the country's code ({@code 01}, {@code 07}, {@code 04}...) or the kind's name
     *                       ({@code INVOICE}, {@code CREDIT_NOTE}, {@code DEBIT_NOTE}); absent = an invoice
     * @param documentNumber the fiscal number in the format of the company's regime
     *                       ({@code F001-123}, {@code 001-001-000000123})
     */
    public record InvoiceRef(
            UUID invoiceId,
            @Size(max = 20) String carrierTaxId,
            @Size(max = 20) String documentType,
            @Size(max = 60) String documentNumber,
            @Size(max = 32) String carrierCode,
            @Size(max = 64) String invoiceNumber) {
    }

    /**
     * @param kind {@code PE_DETRACTION}, {@code PE_RETENTION}, {@code EC_INCOME_TAX_WITHHOLDING} or
     *             {@code EC_VAT_WITHHOLDING}
     */
    public record Withholding(
            @NotBlank @Size(max = 60) String kind,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 60) String certificateNumber,
            LocalDate certificateDate) {
    }
}
