package com.ebim.tms.settlement.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A carrier's invoice, as received (migration V46).
 *
 * <p>The header total is what matching compares - <b>not</b> the sum of the lines. A carrier's
 * document is what it is: rounding, a header-level discount or a line TMS did not receive all make
 * the two disagree legitimately, and refusing the request would make an unrepresentable invoice out
 * of a real one.
 */
public record CarrierInvoiceRequest(
        @NotNull(message = "is required") UUID carrierId,
        /**
         * The carrier's own number. Optional since V66 when the fiscal identity is given: it becomes
         * the normalised fiscal number.
         */
        @Size(max = 60) String invoiceNumber,
        @NotNull(message = "is required") LocalDate invoiceDate,
        /** ISO-4217, and never converted: two currencies do not add up. */
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code")
        String currency,
        /**
         * Non-negative rather than positive: a zero-value invoice is unusual and legitimate - a
         * corrected document, a goodwill run - and refusing it would force somebody to invent a
         * penny.
         */
        @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative")
        BigDecimal totalAmount,
        @Size(max = 120) String externalReference,
        @Size(max = 1000) String notes,
        @NotEmpty(message = "an invoice needs at least one line") @Valid List<LineRequest> lines,
        // ------------------------------------------------------------ V66 (ADR-024 part 2, ADR-027)
        /** INVOICE (default), CREDIT_NOTE or DEBIT_NOTE; the country's code is the regime's. */
        com.ebim.tms.settlement.domain.tax.DocumentKind documentKind,
        /**
         * The fiscal number in the format of the company's regime (Peru {@code F001-123}, Ecuador
         * {@code 001-001-000000123}, free text elsewhere). With {@code issuerTaxId}, or neither.
         */
        @Size(max = 60) String documentNumber,
        /** The issuer's taxpayer id (RUC...), checked by the regime's rule. */
        @Size(max = 20) String issuerTaxId,
        /** Net of VAT. Required with the two below on a document in the regime's fiscal currency. */
        @PositiveOrZero(message = "cannot be negative") BigDecimal subtotalAmount,
        /** The VAT percentage the document states; must be one the regime allows. */
        @PositiveOrZero(message = "cannot be negative") BigDecimal taxRate,
        @PositiveOrZero(message = "cannot be negative") BigDecimal taxAmount,
        /** The invoice a credit or debit note corrects; required on a note, refused on an invoice. */
        UUID referencedInvoiceId,
        /** Certificates of withholdings already deposited (a detraction constancy...). */
        @Valid List<WithholdingCertificate> withholdingCertificates,
        /** Replaces a computed withholding, where its kind allows it; needs the reason. */
        @Valid List<WithholdingOverride> withholdingOverrides) {

    /** A document as every client before V66 sent it: a factura with no fiscal detail. */
    public CarrierInvoiceRequest(UUID carrierId, String invoiceNumber, LocalDate invoiceDate, String currency,
            BigDecimal totalAmount, String externalReference, String notes, List<LineRequest> lines) {
        this(carrierId, invoiceNumber, invoiceDate, currency, totalAmount, externalReference, notes, lines,
                null, null, null, null, null, null, null, null, null);
    }

    public record WithholdingCertificate(
            @NotNull(message = "is required") com.ebim.tms.settlement.domain.tax.WithholdingKind kind,
            @NotBlank(message = "is required") @Size(max = 60) String certificateNumber,
            LocalDate certificateDate) {
    }

    public record WithholdingOverride(
            @NotNull(message = "is required") com.ebim.tms.settlement.domain.tax.WithholdingKind kind,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") BigDecimal amount,
            @NotBlank(message = "is required") @Size(max = 500) String reason) {
    }

    /**
     * @param tripId     <b>optional</b>. An accessorial billed against no particular shipment is a
     *                   real line; requiring a trip would force somebody to attach it to an
     *                   arbitrary one
     * @param chargeCode which charge the line bills (V64). Optional: absent is an all-in
     *                   {@code FREIGHT} line, which is what every client before V64 sent
     */
    public record LineRequest(
            UUID tripId,
            @NotBlank(message = "is required") @Size(max = 300) String description,
            @PositiveOrZero(message = "cannot be negative") BigDecimal quantity,
            @PositiveOrZero(message = "cannot be negative") BigDecimal unitAmount,
            /** Negative only on a {@code PENALTY} line (V66): a deduction, which says why. */
            @NotNull(message = "is required") BigDecimal lineAmount,
            com.ebim.tms.settlement.domain.ChargeCode chargeCode,
            @Size(max = 500) String penaltyReason,
            com.ebim.tms.settlement.domain.PenaltyReferenceType penaltyReferenceType,
            UUID penaltyReferenceId) {

        /** A line as every client before V64 sent it. */
        public LineRequest(UUID tripId, String description, BigDecimal quantity, BigDecimal unitAmount,
                BigDecimal lineAmount) {
            this(tripId, description, quantity, unitAmount, lineAmount, null);
        }

        /** A line as every client before V66 sent it. */
        public LineRequest(UUID tripId, String description, BigDecimal quantity, BigDecimal unitAmount,
                BigDecimal lineAmount, com.ebim.tms.settlement.domain.ChargeCode chargeCode) {
            this(tripId, description, quantity, unitAmount, lineAmount, chargeCode, null, null, null);
        }
    }
}
