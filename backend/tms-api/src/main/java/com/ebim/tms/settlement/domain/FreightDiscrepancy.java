package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One thing wrong with an invoice, with the figures that make it wrong beside it (migration V46).
 *
 * <p>{@code detail} is composed server-side from those figures rather than typed, so the explanation
 * and the numbers cannot drift - the same reason {@code CostComponentReason} is typed and its
 * rendering is not.
 */
@Entity
@Table(name = "freight_discrepancy")
public class FreightDiscrepancy {

    /** Whether somebody has dealt with it. */
    public enum Status { OPEN, ACCEPTED, REJECTED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "carrier_invoice_id", updatable = false, nullable = false)
    private UUID carrierInvoiceId;

    @Column(name = "invoice_line_id")
    private UUID invoiceLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private DiscrepancyType type;

    @Column(name = "expected_amount")
    private BigDecimal expectedAmount;

    @Column(name = "invoiced_amount")
    private BigDecimal invoicedAmount;

    @Column(name = "difference_amount")
    private BigDecimal differenceAmount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "detail", nullable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FreightDiscrepancy() {
    }

    public FreightDiscrepancy(UUID companyId, UUID carrierInvoiceId, UUID invoiceLineId,
            DiscrepancyType type, BigDecimal expectedAmount, BigDecimal invoicedAmount,
            BigDecimal differenceAmount, String currency, String detail) {
        this.companyId = companyId;
        this.carrierInvoiceId = carrierInvoiceId;
        this.invoiceLineId = invoiceLineId;
        this.type = type;
        this.expectedAmount = expectedAmount;
        this.invoicedAmount = invoicedAmount;
        this.differenceAmount = differenceAmount;
        this.currency = currency;
        this.detail = detail;
    }

    /**
     * Somebody dealt with it.
     *
     * <p>{@code ACCEPTED} means the difference is legitimate and the invoice may proceed;
     * {@code REJECTED} means it is not. Neither changes a figure - a discrepancy is a fact about
     * what was compared, and resolving it records a judgement rather than rewriting the comparison.
     */
    public void resolve(Status decision, String notes, UUID actorId) {
        if (decision == Status.OPEN) {
            throw new IllegalArgumentException("resolving a discrepancy means accepting or rejecting it");
        }
        this.status = decision;
        this.resolutionNotes = notes;
        this.resolvedAt = OffsetDateTime.now();
        this.resolvedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID carrierInvoiceId() {
        return carrierInvoiceId;
    }

    public UUID invoiceLineId() {
        return invoiceLineId;
    }

    public DiscrepancyType type() {
        return type;
    }

    public BigDecimal expectedAmount() {
        return expectedAmount;
    }

    public BigDecimal invoicedAmount() {
        return invoicedAmount;
    }

    public BigDecimal differenceAmount() {
        return differenceAmount;
    }

    public String detail() {
        return detail;
    }

    public Status status() {
        return status;
    }

    public String resolutionNotes() {
        return resolutionNotes;
    }

    public OffsetDateTime resolvedAt() {
        return resolvedAt;
    }
}
