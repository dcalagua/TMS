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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A payment keyed by a person, waiting for a second person (migration V67, ADR-023 section 2).
 *
 * <p>The maker/checker rule of approval applied to the other end of the obligation: separate
 * permissions make the two authorities separable, and {@link #confirm} refusing the requester is what
 * makes them separated. Confirming inserts the {@link CarrierPayment}; rejecting says why.
 */
@Entity
@Table(name = "carrier_payment_request")
public class CarrierPaymentRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "carrier_invoice_id", updatable = false, nullable = false)
    private UUID carrierInvoiceId;

    @Column(name = "carrier_id", updatable = false, nullable = false)
    private UUID carrierId;

    @Column(name = "payment_reference", updatable = false, nullable = false)
    private String paymentReference;

    @Column(name = "paid_on", updatable = false, nullable = false)
    private LocalDate paidOn;

    @Column(name = "amount", updatable = false, nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", updatable = false, nullable = false, length = 3)
    private String currency;

    @Column(name = "detraction_constancy_number", updatable = false)
    private String detractionConstancyNumber;

    @Column(name = "detraction_constancy_date", updatable = false)
    private LocalDate detractionConstancyDate;

    @Column(name = "detraction_amount", updatable = false, precision = 14, scale = 2)
    private BigDecimal detractionAmount;

    @Column(name = "retention_amount", updatable = false, precision = 14, scale = 2)
    private BigDecimal retentionAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentRequestStatus status = PaymentRequestStatus.PENDING;

    @Column(name = "requested_by", updatable = false, nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", updatable = false, nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    @Column(name = "carrier_payment_id")
    private UUID carrierPaymentId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CarrierPaymentRequest() {
    }

    public CarrierPaymentRequest(CarrierInvoice invoice, PaymentDetail detail, UUID requestedBy) {
        this.companyId = invoice.companyId();
        this.carrierInvoiceId = invoice.id();
        this.carrierId = invoice.carrierId();
        this.paymentReference = detail.paymentReference();
        this.paidOn = detail.paidOn();
        this.amount = detail.amount();
        this.currency = detail.currency();
        this.detractionConstancyNumber = detail.detractionConstancyNumber();
        this.detractionConstancyDate = detail.detractionConstancyDate();
        this.detractionAmount = detail.detractionAmount();
        this.retentionAmount = detail.retentionAmount();
        this.requestedBy = requestedBy;
        this.requestedAt = OffsetDateTime.now();
    }

    /**
     * A different person confirms; the payment row is the caller's to insert first, so this names it.
     *
     * @throws ConflictException when the requester tries to confirm their own payment, or it was decided
     */
    public void confirm(UUID decidedBy, UUID carrierPaymentId) {
        requirePending();
        requireDifferentPerson(decidedBy);
        this.status = PaymentRequestStatus.CONFIRMED;
        this.decidedBy = decidedBy;
        this.decidedAt = OffsetDateTime.now();
        this.carrierPaymentId = carrierPaymentId;
    }

    /**
     * Refused, with a reason. Unlike confirmation, the requester may withdraw their own request: refusing
     * to record a payment moves nothing, the asymmetry {@code SettlementService.requireDifferentApprover}
     * draws for rejection.
     */
    public void reject(UUID decidedBy, String comment) {
        requirePending();
        if (comment == null || comment.isBlank()) {
            throw new InvalidRequestException("Rejecting a payment says why.");
        }
        if (comment.trim().length() > 500) {
            throw new InvalidRequestException("A reason is at most 500 characters.");
        }
        this.status = PaymentRequestStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decidedAt = OffsetDateTime.now();
        this.decisionComment = comment.trim();
    }

    /** The four-eyes rule, checked before the payment row is written. */
    public void requireDifferentPerson(UUID decidedBy) {
        if (requestedBy.equals(decidedBy)) {
            throw new ConflictException("The person who keyed a payment cannot confirm it."
                    + " Somebody else has to confirm this payment.");
        }
    }

    public void requirePending() {
        if (status != PaymentRequestStatus.PENDING) {
            throw new ConflictException("This payment was already " + status.name().toLowerCase(java.util.Locale.ROOT)
                    + ".");
        }
    }

    public PaymentDetail detail() {
        return new PaymentDetail(paymentReference, paidOn, amount, currency, detractionConstancyNumber,
                detractionConstancyDate, detractionAmount, retentionAmount);
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID carrierInvoiceId() {
        return carrierInvoiceId;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public PaymentRequestStatus status() {
        return status;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public OffsetDateTime requestedAt() {
        return requestedAt;
    }

    public UUID decidedBy() {
        return decidedBy;
    }

    public OffsetDateTime decidedAt() {
        return decidedAt;
    }

    public String decisionComment() {
        return decisionComment;
    }

    public UUID carrierPaymentId() {
        return carrierPaymentId;
    }
}
