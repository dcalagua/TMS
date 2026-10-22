package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.ClaimResolution;
import com.ebim.tms.settlement.domain.ClaimType;
import com.ebim.tms.settlement.domain.RecoveryMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The bodies of the freight claim endpoints (V71). */
public final class FreightClaimRequests {

    private FreightClaimRequests() {
    }

    /**
     * Opens a claim. The carrier is never sent: it is the trip's, so a claim cannot name a carrier
     * that did not run the shipment.
     *
     * @param orderDeliveryId the delivery the claim comes from; its order becomes the claim's
     * @param tripExceptionId the exception the claim comes from
     * @param claimedAmount   the amount asked for; with lines, their sum, and it may be left out
     */
    public record OpenClaim(
            @NotNull(message = "is required") UUID tripId,
            UUID orderId,
            UUID orderDeliveryId,
            UUID tripExceptionId,
            @NotNull(message = "is required") ClaimType claimType,
            @NotNull(message = "is required") LocalDate incidentDate,
            @Size(max = 2000) String description,
            @Positive(message = "must be greater than zero") BigDecimal claimedAmount,
            @Size(min = 3, max = 3, message = "is a three-letter ISO code") String currency,
            @Valid @Size(max = 100) List<ClaimLine> lines) {
    }

    /** Corrects an OPEN claim: what it says, never what it is about. */
    public record UpdateClaim(
            @NotNull(message = "is required") ClaimType claimType,
            @NotNull(message = "is required") LocalDate incidentDate,
            @Size(max = 2000) String description,
            @Positive(message = "must be greater than zero") BigDecimal claimedAmount,
            @Size(min = 3, max = 3, message = "is a three-letter ISO code") String currency,
            @Valid @Size(max = 100) List<ClaimLine> lines) {
    }

    /** One part of a claim. {@code orderLineId} needs the claim to name its order. */
    public record ClaimLine(
            UUID orderLineId,
            @Size(max = 500) String description,
            @Positive(message = "must be greater than zero") BigDecimal quantity,
            @Size(max = 20) String uom,
            @NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") BigDecimal amount) {
    }

    /**
     * The decision. {@code acceptedAmount} only for a partial agreement - an acceptance takes the
     * claimed amount whole - and {@code recoveryMethod} for both acceptances.
     */
    public record Decide(
            @NotNull(message = "is required") ClaimResolution decision,
            @Positive(message = "must be greater than zero") BigDecimal acceptedAmount,
            RecoveryMethod recoveryMethod,
            @Size(max = 500) String reason) {
    }

    /** Files a decided claim; an accepted one names the carrier document that recovered it. */
    public record Close(UUID settlementInvoiceId, @Size(max = 500) String notes) {
    }

    /** Cancelling says why. */
    public record Cancel(@Size(max = 500) String reason) {
    }
}
