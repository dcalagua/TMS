package com.ebim.tms.settlement.domain;

import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A claim against the carrier of a trip (migration V71).
 *
 * <p>The lifecycle is the whole of this class, and every rule it holds is held a second time by a
 * CHECK of {@code tms.freight_claim}: a transition the code allows and the database does not is a
 * rolled-back request, never a row in a state nobody designed.
 *
 * <pre>
 *   OPEN -> UNDER_REVIEW -> ACCEPTED | PARTIALLY_ACCEPTED | REJECTED -> CLOSED
 *   OPEN | UNDER_REVIEW -> CANCELLED
 * </pre>
 *
 * <p><b>Four eyes on money.</b> Accepting a claim - in whole or in part - decides what the carrier
 * owes, and whoever opened it cannot do that: the maker/checker rule settlement already applies to
 * approving an invoice and confirming a payment. Rejecting moves nothing and is allowed to anyone
 * with the authority to decide, the same asymmetry {@code CarrierPaymentRequest#reject} draws.
 *
 * <p><b>TMS moves no money.</b> An accepted claim names how it is recovered - a PENALTY line on the
 * carrier's invoice or a CREDIT_NOTE from the carrier - and is closed by the document that did it.
 */
@Entity
@Table(name = "freight_claim")
public class FreightClaim {

    /** The CHECKs' limits, stated once. */
    public static final int DESCRIPTION_MAX = 2000;
    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "claim_number", updatable = false, nullable = false)
    private String claimNumber;

    @Column(name = "carrier_id", updatable = false, nullable = false)
    private UUID carrierId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "order_delivery_id", updatable = false)
    private UUID orderDeliveryId;

    @Column(name = "trip_exception_id", updatable = false)
    private UUID tripExceptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false)
    private ClaimType claimType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.OPEN;

    @Column(name = "incident_date", nullable = false)
    private LocalDate incidentDate;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "claimed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution")
    private ClaimResolution resolution;

    @Column(name = "accepted_amount", precision = 14, scale = 2)
    private BigDecimal acceptedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_method")
    private RecoveryMethod recoveryMethod;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "opened_by", updatable = false, nullable = false)
    private UUID openedBy;

    @Column(name = "opened_at", updatable = false, nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "close_notes")
    private String closeNotes;

    @Column(name = "settlement_invoice_id")
    private UUID settlementInvoiceId;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FreightClaim() {
        // JPA
    }

    /** What a claim is about. Every link is optional except the trip and its carrier. */
    public record Subject(UUID carrierId, UUID tripId, UUID orderId, UUID orderDeliveryId, UUID tripExceptionId) {
    }

    /** What the person opening or correcting a claim states. */
    public record Terms(ClaimType type, LocalDate incidentDate, String description, BigDecimal claimedAmount,
            String currency) {
    }

    public FreightClaim(UUID companyId, String claimNumber, Subject subject, Terms terms, UUID openedBy,
            OffsetDateTime openedAt) {
        this.companyId = companyId;
        this.claimNumber = claimNumber;
        this.carrierId = subject.carrierId();
        this.tripId = subject.tripId();
        this.orderId = subject.orderId();
        this.orderDeliveryId = subject.orderDeliveryId();
        this.tripExceptionId = subject.tripExceptionId();
        this.openedBy = openedBy;
        this.openedAt = openedAt;
        apply(terms);
    }

    /** Corrects what an OPEN claim says. Once submitted, it is frozen until somebody decides it. */
    public void update(Terms terms) {
        requireStatus(ClaimStatus.OPEN, "edited");
        apply(terms);
    }

    /** OPEN -> UNDER_REVIEW: the claim is complete and somebody with the authority can decide it. */
    public void submit(UUID actor, OffsetDateTime at) {
        requireStatus(ClaimStatus.OPEN, "sent for review");
        this.status = ClaimStatus.UNDER_REVIEW;
        this.submittedBy = actor;
        this.submittedAt = at;
    }

    /** Accepted in full: the carrier owes what was claimed. Four eyes. */
    public void accept(UUID actor, RecoveryMethod method, String reason, OffsetDateTime at) {
        requireStatus(ClaimStatus.UNDER_REVIEW, "accepted");
        requireDifferentPerson(actor);
        requireMethod(method);
        decide(ClaimResolution.ACCEPTED, claimedAmount, method, optionalReason(reason), actor, at);
    }

    /** Accepted in part - the agreement, which always says why it is less. Four eyes. */
    public void partiallyAccept(UUID actor, BigDecimal amount, RecoveryMethod method, String reason,
            OffsetDateTime at) {
        requireStatus(ClaimStatus.UNDER_REVIEW, "partially accepted");
        requireDifferentPerson(actor);
        requireMethod(method);
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestException("A partial agreement accepts an amount greater than zero.");
        }
        BigDecimal accepted = amount.setScale(2, RoundingMode.HALF_UP);
        if (accepted.compareTo(claimedAmount) >= 0) {
            throw new InvalidRequestException("A partial agreement accepts less than the "
                    + claimedAmount.toPlainString() + " " + currency + " claimed; to accept all of it, accept the claim.");
        }
        decide(ClaimResolution.PARTIALLY_ACCEPTED, accepted, method, requiredReason(reason, "A partial agreement"),
                actor, at);
    }

    /** Rejected, with a reason. Allowed to whoever opened it: refusing moves nothing. */
    public void reject(UUID actor, String reason, OffsetDateTime at) {
        requireStatus(ClaimStatus.UNDER_REVIEW, "rejected");
        decide(ClaimResolution.REJECTED, null, null, requiredReason(reason, "Rejecting a claim"), actor, at);
    }

    /**
     * Files a decided claim. An accepted one is closed by the carrier document that recovered it -
     * checked by the caller against the recovery method, because that needs the invoice - and a
     * rejected one by nothing.
     */
    public void close(UUID actor, UUID recoveringInvoiceId, String notes, OffsetDateTime at) {
        if (!status.isDecided()) {
            throw new ConflictException("Claim " + claimNumber + " is " + label(status)
                    + "; only a decided claim can be closed.");
        }
        if (resolution.recovers() && recoveringInvoiceId == null) {
            throw new InvalidRequestException("An accepted claim is closed by the carrier document that recovered it: "
                    + (recoveryMethod == RecoveryMethod.PENALTY ? "the invoice with its penalty line."
                            : "the credit note the carrier sent."));
        }
        if (!resolution.recovers() && recoveringInvoiceId != null) {
            throw new InvalidRequestException("A rejected claim recovers nothing, so no document closes it.");
        }
        String trimmed = blankToNull(notes);
        if (trimmed != null && trimmed.length() > REASON_MAX) {
            throw new InvalidRequestException("Closing notes are at most " + REASON_MAX + " characters.");
        }
        this.status = ClaimStatus.CLOSED;
        this.settlementInvoiceId = recoveringInvoiceId;
        this.closeNotes = trimmed;
        this.closedBy = actor;
        this.closedAt = at;
    }

    /** Withdrawn before a decision, with a reason. A decided claim is closed, not cancelled. */
    public void cancel(String reason) {
        if (status != ClaimStatus.OPEN && status != ClaimStatus.UNDER_REVIEW) {
            throw new ConflictException("Claim " + claimNumber + " is " + label(status)
                    + "; only an open or in-review claim can be cancelled.");
        }
        this.cancellationReason = requiredReason(reason, "Cancelling a claim");
        this.status = ClaimStatus.CANCELLED;
    }

    /** Evidence can be added until the claim is filed: the carrier's reply often brings more. */
    public void requireAcceptsEvidence() {
        if (status.isFinished()) {
            throw new ConflictException("Claim " + claimNumber + " is " + label(status) + "; it takes no more evidence.");
        }
    }

    /** What the state allows next; the permission and the four eyes are checked when it is done. */
    public List<ClaimAction> allowedActions() {
        List<ClaimAction> actions = new ArrayList<>();
        switch (status) {
            case OPEN -> actions.addAll(List.of(ClaimAction.UPDATE, ClaimAction.SUBMIT, ClaimAction.CANCEL));
            case UNDER_REVIEW -> actions.addAll(List.of(ClaimAction.ACCEPT, ClaimAction.PARTIALLY_ACCEPT,
                    ClaimAction.REJECT, ClaimAction.CANCEL));
            case ACCEPTED, PARTIALLY_ACCEPTED, REJECTED -> actions.add(ClaimAction.CLOSE);
            default -> {
                // CLOSED and CANCELLED are terminal.
            }
        }
        if (!status.isFinished()) {
            actions.add(ClaimAction.ATTACH_EVIDENCE);
        }
        return List.copyOf(actions);
    }

    /** The four-eyes rule, checked before anything is written. */
    public void requireDifferentPerson(UUID actor) {
        if (openedBy.equals(actor)) {
            throw new ConflictException("The person who opened a claim cannot accept it."
                    + " Somebody else has to decide what the carrier owes.");
        }
    }

    private void decide(ClaimResolution decision, BigDecimal accepted, RecoveryMethod method, String reason,
            UUID actor, OffsetDateTime at) {
        this.resolution = decision;
        this.status = decision.status();
        this.acceptedAmount = accepted;
        this.recoveryMethod = method;
        this.decisionReason = reason;
        this.decidedBy = actor;
        this.decidedAt = at;
    }

    private void apply(Terms terms) {
        if (terms.type() == null) {
            throw new InvalidRequestException("A claim says what it is for: damage, shortage, loss, delay, wrong"
                    + " delivery or other.");
        }
        if (terms.incidentDate() == null) {
            throw new InvalidRequestException("A claim says when the incident happened.");
        }
        String text = blankToNull(terms.description());
        if (text == null) {
            throw new InvalidRequestException("A claim describes what happened.");
        }
        if (text.length() > DESCRIPTION_MAX) {
            throw new InvalidRequestException("A description is at most " + DESCRIPTION_MAX + " characters.");
        }
        if (terms.claimedAmount() == null || terms.claimedAmount().signum() <= 0) {
            throw new InvalidRequestException("A claim asks for an amount greater than zero.");
        }
        String code = terms.currency() == null ? "" : terms.currency().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            throw new InvalidRequestException("The currency is a three-letter ISO code (USD, PEN).");
        }
        this.claimType = terms.type();
        this.incidentDate = terms.incidentDate();
        this.description = text;
        this.claimedAmount = terms.claimedAmount().setScale(2, RoundingMode.HALF_UP);
        this.currency = code;
    }

    private void requireStatus(ClaimStatus expected, String verb) {
        if (status != expected) {
            throw new ConflictException("Claim " + claimNumber + " is " + label(status) + " and cannot be " + verb
                    + ".");
        }
    }

    private static void requireMethod(RecoveryMethod method) {
        if (method == null) {
            throw new InvalidRequestException("Accepting a claim says how it is recovered: a penalty on the carrier's"
                    + " invoice or a credit note from the carrier.");
        }
    }

    private static String optionalReason(String reason) {
        String trimmed = blankToNull(reason);
        if (trimmed != null && trimmed.length() > REASON_MAX) {
            throw new InvalidRequestException("A reason is at most " + REASON_MAX + " characters.");
        }
        return trimmed;
    }

    private static String requiredReason(String reason, String what) {
        String trimmed = optionalReason(reason);
        if (trimmed == null) {
            throw new InvalidRequestException(what + " says why.");
        }
        return trimmed;
    }

    private static String label(ClaimStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String claimNumber() {
        return claimNumber;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public UUID tripId() {
        return tripId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID orderDeliveryId() {
        return orderDeliveryId;
    }

    public UUID tripExceptionId() {
        return tripExceptionId;
    }

    public ClaimType claimType() {
        return claimType;
    }

    public ClaimStatus status() {
        return status;
    }

    public LocalDate incidentDate() {
        return incidentDate;
    }

    public String description() {
        return description;
    }

    public BigDecimal claimedAmount() {
        return claimedAmount;
    }

    public String currency() {
        return currency;
    }

    public ClaimResolution resolution() {
        return resolution;
    }

    public BigDecimal acceptedAmount() {
        return acceptedAmount;
    }

    public RecoveryMethod recoveryMethod() {
        return recoveryMethod;
    }

    public String decisionReason() {
        return decisionReason;
    }

    public UUID decidedBy() {
        return decidedBy;
    }

    public OffsetDateTime decidedAt() {
        return decidedAt;
    }

    public UUID openedBy() {
        return openedBy;
    }

    public OffsetDateTime openedAt() {
        return openedAt;
    }

    public UUID submittedBy() {
        return submittedBy;
    }

    public OffsetDateTime submittedAt() {
        return submittedAt;
    }

    public UUID closedBy() {
        return closedBy;
    }

    public OffsetDateTime closedAt() {
        return closedAt;
    }

    public String closeNotes() {
        return closeNotes;
    }

    public UUID settlementInvoiceId() {
        return settlementInvoiceId;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public long version() {
        return version;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
