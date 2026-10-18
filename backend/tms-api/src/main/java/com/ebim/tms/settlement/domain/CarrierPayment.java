package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * A payment of a carrier invoice, as the ERP reported it or two people keyed it (migration V67,
 * ADR-023 section 2). <b>TMS paid nothing</b>: this is the record of what somebody else paid (D-01).
 *
 * <p>{@code @Immutable} so Hibernate never issues an UPDATE, and in the database both the grant
 * ({@code SELECT, INSERT}) and {@code tr_carrier_payment_append_only}. A wrong payment is corrected by
 * the ERP with another document, never by editing this one.
 */
@Entity
@Immutable
@Table(name = "carrier_payment")
public class CarrierPayment {

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

    /** The withholdings as reported with the payment (ADR-027); informative. */
    @jakarta.persistence.Convert(converter = ReportedWithholdingsConverter.class)
    @Column(name = "reported_withholdings", updatable = false, nullable = false)
    private java.util.List<ReportedWithholding> reportedWithholdings = java.util.List.of();

    @Enumerated(EnumType.STRING)
    @Column(name = "source", updatable = false, nullable = false)
    private PaymentSource source;

    @Column(name = "recorded_by_client", updatable = false)
    private UUID recordedByClient;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @Column(name = "confirmed_by", updatable = false)
    private UUID confirmedBy;

    @Column(name = "payment_request_id", updatable = false)
    private UUID paymentRequestId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected CarrierPayment() {
    }

    private CarrierPayment(CarrierInvoice invoice, PaymentDetail detail, PaymentSource source) {
        this.companyId = invoice.companyId();
        this.carrierInvoiceId = invoice.id();
        this.carrierId = invoice.carrierId();
        this.paymentReference = detail.paymentReference();
        this.paidOn = detail.paidOn();
        this.amount = detail.amount();
        this.currency = detail.currency();
        this.reportedWithholdings = detail.withholdings();
        this.source = source;
    }

    /** Reported by the ERP's credential. */
    public static CarrierPayment reportedBy(UUID integrationClientId, CarrierInvoice invoice, PaymentDetail detail) {
        CarrierPayment payment = new CarrierPayment(invoice, detail, PaymentSource.INTEGRATION);
        payment.recordedByClient = integrationClientId;
        return payment;
    }

    /** Keyed by one person and confirmed by another ({@code ck_carrier_payment_attribution}). */
    public static CarrierPayment confirmed(CarrierPaymentRequest request, CarrierInvoice invoice, UUID confirmedBy) {
        if (request.requestedBy().equals(confirmedBy)) {
            throw new IllegalArgumentException("the person who keyed a payment cannot confirm it");
        }
        CarrierPayment payment = new CarrierPayment(invoice, request.detail(), PaymentSource.OPERATOR);
        payment.recordedBy = request.requestedBy();
        payment.confirmedBy = confirmedBy;
        payment.paymentRequestId = request.id();
        return payment;
    }

    public PaymentDetail detail() {
        return new PaymentDetail(paymentReference, paidOn, amount, currency, reportedWithholdings);
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

    public LocalDate paidOn() {
        return paidOn;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public java.util.List<ReportedWithholding> reportedWithholdings() {
        return reportedWithholdings;
    }

    public PaymentSource source() {
        return source;
    }

    public UUID recordedByClient() {
        return recordedByClient;
    }

    public UUID recordedBy() {
        return recordedBy;
    }

    public UUID confirmedBy() {
        return confirmedBy;
    }

    public UUID paymentRequestId() {
        return paymentRequestId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
