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
        @NotBlank(message = "is required") @Size(max = 60) String invoiceNumber,
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
        @NotEmpty(message = "an invoice needs at least one line") @Valid List<LineRequest> lines) {

    /**
     * @param tripId <b>optional</b>. An accessorial billed against no particular shipment is a real
     *               line; requiring a trip would force somebody to attach it to an arbitrary one
     */
    public record LineRequest(
            UUID tripId,
            @NotBlank(message = "is required") @Size(max = 300) String description,
            @PositiveOrZero(message = "cannot be negative") BigDecimal quantity,
            @PositiveOrZero(message = "cannot be negative") BigDecimal unitAmount,
            @NotNull(message = "is required") BigDecimal lineAmount) {
    }
}
