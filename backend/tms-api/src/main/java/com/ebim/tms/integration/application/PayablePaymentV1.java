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
import java.util.UUID;

/**
 * One payment the ERP made against an exported carrier invoice ({@code POST /integration/v1/payable-payments},
 * {@code INBOUND_API_V1.md} section 12, ADR-023 section 2). TMS records it; it moves no money (D-01).
 *
 * @param invoice          how the ERP names the invoice: exactly one of {@code invoiceId}, the CPE, or
 *                         {@code carrierCode} + {@code invoiceNumber}
 * @param paymentReference the ERP's own reference; repeating it with the same figures is a {@code DUPLICATE}
 * @param amount           paid to the carrier, in the invoice's currency, never more than the open balance
 * @param detraction       the deposit in the carrier's detraction account, when the invoice carries one
 * @param retentionAmount  withheld and paid to SUNAT; informative
 */
public record PayablePaymentV1(
        @NotNull @Valid InvoiceRef invoice,
        @NotBlank @Size(max = 80) String paymentReference,
        @NotNull LocalDate paidOn,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
        @Valid Detraction detraction,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal retentionAmount,
        @Pattern(regexp = "^[A-Z0-9_]{2,40}$") String sourceSystem) {

    /** @param carrierTaxId the issuer RUC printed on the CPE */
    public record InvoiceRef(
            UUID invoiceId,
            @Size(max = 11) String carrierTaxId,
            @Pattern(regexp = "^(01|07|08)$") String documentType,
            @Size(max = 4) String series,
            @Size(max = 8) String correlative,
            @Size(max = 32) String carrierCode,
            @Size(max = 64) String invoiceNumber) {
    }

    public record Detraction(
            @NotBlank @Size(max = 40) String constancyNumber,
            LocalDate constancyDate,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }
}
