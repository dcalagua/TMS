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
         * The carrier's own number. Optional since V66 when the CPE identity is given: it becomes
         * {@code series-correlative}.
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
        // ------------------------------------------------------------ V66 (ADR-024 part 2)
        /** SUNAT type: {@code 01} factura (default), {@code 07} credit note, {@code 08} debit note. */
        @Pattern(regexp = "^(01|07|08)$", message = "must be 01, 07 or 08") String documentType,
        @Pattern(regexp = "^(F[A-Z0-9]{3}|E001|[0-9]{4})$", message = "must be F + 3 characters, E001 or 4 digits")
        String series,
        @Pattern(regexp = "^[0-9]{1,8}$", message = "must be 1 to 8 digits") String correlative,
        @Pattern(regexp = "^[0-9]{11}$", message = "must be 11 digits") String issuerRuc,
        /** Valor de venta, net of IGV. Required with the two below on a PEN document. */
        @PositiveOrZero(message = "cannot be negative") BigDecimal subtotalAmount,
        @PositiveOrZero(message = "cannot be negative") BigDecimal igvRate,
        @PositiveOrZero(message = "cannot be negative") BigDecimal igvAmount,
        /** The invoice a credit or debit note corrects; required on 07/08, refused on 01. */
        UUID referencedInvoiceId,
        @Size(max = 40) String detractionConstancyNumber,
        LocalDate detractionConstancyDate,
        /** Replaces the computed retention; needs {@code retentionOverrideReason}. */
        @PositiveOrZero(message = "cannot be negative") BigDecimal retentionOverrideAmount,
        @Size(max = 500) String retentionOverrideReason) {

    /** A document as every client before V66 sent it: a factura with no fiscal detail. */
    public CarrierInvoiceRequest(UUID carrierId, String invoiceNumber, LocalDate invoiceDate, String currency,
            BigDecimal totalAmount, String externalReference, String notes, List<LineRequest> lines) {
        this(carrierId, invoiceNumber, invoiceDate, currency, totalAmount, externalReference, notes, lines,
                null, null, null, null, null, null, null, null, null, null, null, null);
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
