package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.ClaimAction;
import com.ebim.tms.settlement.domain.ClaimResolution;
import com.ebim.tms.settlement.domain.ClaimStatus;
import com.ebim.tms.settlement.domain.ClaimType;
import com.ebim.tms.settlement.domain.RecoveryMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** What the freight claim endpoints answer (V71). Amounts are per currency: TMS converts nothing. */
public final class FreightClaimViews {

    private FreightClaimViews() {
    }

    /** One row of the claims list. {@code ageDays} counts to today while active, to the close once filed. */
    public record ClaimSummary(
            UUID id,
            String claimNumber,
            ClaimType claimType,
            ClaimStatus status,
            ClaimResolution resolution,
            UUID carrierId,
            String carrierName,
            UUID tripId,
            String shipmentNumber,
            UUID orderId,
            String orderNumber,
            LocalDate incidentDate,
            OffsetDateTime openedAt,
            long ageDays,
            BigDecimal claimedAmount,
            BigDecimal acceptedAmount,
            String currency,
            RecoveryMethod recoveryMethod) {
    }

    /**
     * One claim, whole.
     *
     * @param openedByMe       whether the caller opened it - the four-eyes rule says they cannot accept
     *                         it, so a screen can say so before the button is pressed
     * @param penalties        the live penalty lines that recover it, on documents not rejected
     * @param allowedActions   what the state allows; the permission and the four eyes are re-checked
     */
    public record ClaimDetail(
            ClaimSummary summary,
            String description,
            UUID orderDeliveryId,
            UUID tripExceptionId,
            UUID openedBy,
            boolean openedByMe,
            UUID submittedBy,
            OffsetDateTime submittedAt,
            String decisionReason,
            UUID decidedBy,
            OffsetDateTime decidedAt,
            UUID closedBy,
            OffsetDateTime closedAt,
            String closeNotes,
            UUID settlementInvoiceId,
            String settlementInvoiceNumber,
            String cancellationReason,
            List<ClaimLineView> lines,
            List<ClaimEvidenceView> evidence,
            List<PenaltyLineView> penalties,
            List<ClaimAction> allowedActions,
            long version) {
    }

    /** One part of a claim, with the order line's number and material when it names one. */
    public record ClaimLineView(
            UUID id,
            int lineNumber,
            UUID orderLineId,
            Integer orderLineNumber,
            String materialCode,
            String description,
            BigDecimal quantity,
            String uom,
            BigDecimal amount) {
    }

    public record ClaimEvidenceView(
            UUID id,
            String contentType,
            long sizeBytes,
            String originalFilename,
            String description,
            UUID uploadedBy,
            OffsetDateTime createdAt) {
    }

    /** A penalty line on a carrier document that recovers this claim. {@code amount} is negative. */
    public record PenaltyLineView(UUID invoiceId, String invoiceNumber, String invoiceStatus, UUID lineId,
            BigDecimal amount) {
    }

    /**
     * What a new claim would say, read from what was recorded about the trip - nothing is written.
     *
     * @param claimable       false when nobody can be claimed from: an own-fleet trip, or one that never ran
     * @param notClaimableReason why, in a sentence
     * @param suggestedType   from the delivery result or the exception type; the person can change it
     * @param refusedLines    the order lines with refused quantities, to prefill the claim's lines
     * @param existingClaims  claims already open on the same trip, so the same broken chairs are not
     *                        claimed twice
     */
    public record ClaimDraft(
            UUID tripId,
            String shipmentNumber,
            UUID carrierId,
            String carrierName,
            boolean claimable,
            String notClaimableReason,
            UUID orderId,
            String orderNumber,
            UUID orderDeliveryId,
            String deliveryResult,
            String deliveryNotes,
            BigDecimal refusedWeightKg,
            BigDecimal refusedPallets,
            UUID tripExceptionId,
            String exceptionType,
            String exceptionNotes,
            ClaimType suggestedType,
            LocalDate incidentDate,
            List<DraftLine> refusedLines,
            List<ExistingClaim> existingClaims) {
    }

    public record DraftLine(UUID orderLineId, Integer orderLineNumber, String materialCode, BigDecimal quantity,
            String uom, String notes) {
    }

    public record ExistingClaim(UUID id, String claimNumber, ClaimStatus status, ClaimType claimType) {
    }

    /**
     * The figures at the top of the claims screen: what is still active, by status and currency, and
     * how old it is.
     *
     * @param awaitingRecovery accepted claims whose recovery is not yet documented
     */
    public record ClaimsOverview(
            long activeCount,
            long openCount,
            long underReviewCount,
            long awaitingRecovery,
            List<CurrencyTotal> currencies,
            AgeingBuckets ageing) {
    }

    /** {@code acceptedPending}: accepted and not yet recovered. */
    public record CurrencyTotal(String currency, long count, BigDecimal claimedAmount, BigDecimal acceptedPending) {
    }

    /** Active claims and open exceptions by age in days: 0-7, 8-30, 31-60, more than 60. */
    public record AgeingBuckets(long upTo7, long from8To30, long from31To60, long over60) {

        public static AgeingBuckets of(List<Long> ages) {
            long a = 0;
            long b = 0;
            long c = 0;
            long d = 0;
            for (long age : ages) {
                if (age <= 7) {
                    a++;
                } else if (age <= 30) {
                    b++;
                } else if (age <= 60) {
                    c++;
                } else {
                    d++;
                }
            }
            return new AgeingBuckets(a, b, c, d);
        }
    }

    /**
     * An accepted claim the invoice being keyed could deduct, as the PENALTY line it would become.
     * A proposal: the line is applied only when the person keying the invoice adds it and saves.
     *
     * @param penaltyAmount negative - a penalty is a deduction (V66)
     */
    public record PenaltySuggestion(
            UUID claimId,
            String claimNumber,
            ClaimType claimType,
            UUID tripId,
            String shipmentNumber,
            BigDecimal acceptedAmount,
            String currency,
            BigDecimal penaltyAmount,
            String penaltyReason) {
    }
}
