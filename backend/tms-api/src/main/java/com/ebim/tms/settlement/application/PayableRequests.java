package com.ebim.tms.settlement.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request bodies of the office's payable actions (V67, ADR-023 section 2). */
public final class PayableRequests {

    private PayableRequests() {
    }

    public record ScheduleRequest(@NotNull LocalDate scheduledPaymentDate) {
    }

    /** Placing and releasing a hold, and rejecting a keyed payment: each says why. */
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** A payment keyed by a person; it waits for a different person to confirm it. */
    public record ManualPaymentRequest(
            @NotBlank @Size(max = 80) String paymentReference,
            @NotNull LocalDate paidOn,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Size(max = 40) String detractionConstancyNumber,
            LocalDate detractionConstancyDate,
            @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal detractionAmount,
            @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal retentionAmount) {
    }

    public record DecisionRequest(@NotBlank @Size(max = 500) String comment) {
    }
}
