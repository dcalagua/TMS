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
            /** What was withheld and deposited, with its certificate (ADR-027); informative. */
            @jakarta.validation.Valid java.util.List<ReportedWithholdingRequest> withholdings) {
    }

    /**
     * One withholding reported with a payment: its kind must be one the invoice carries. The amount is
     * informative and may be absent when only the certificate is being recorded.
     */
    public record ReportedWithholdingRequest(
            @jakarta.validation.constraints.NotNull com.ebim.tms.settlement.domain.tax.WithholdingKind kind,
            @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 60) String certificateNumber,
            LocalDate certificateDate) {
    }

    public record DecisionRequest(@NotBlank @Size(max = 500) String comment) {
    }
}
