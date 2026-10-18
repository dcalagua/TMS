package com.ebim.tms.settlement.domain;

import com.ebim.tms.shared.api.ConflictException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * What a carrier says it is owed (migration V46).
 *
 * <p>TMS validates this document and exports the approved obligation. <b>It does not pay.</b> There
 * is no ledger here, no bank detail and no accounting period - that boundary is the whole design,
 * and V30 already stated it on {@code trip_cost.actual_reference}.
 *
 * <p>The aggregate owns its lines and its own status. It does <b>not</b> own the match, the
 * discrepancies, the approvals or the export: each of those is a record of something that happened
 * to the invoice, written once and read back, and folding them into this entity would make a
 * correction to a line look like a correction to an approval.
 */
@Entity
@Table(name = "carrier_invoice")
public class CarrierInvoice {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "carrier_id", updatable = false, nullable = false)
    private UUID carrierId;

    /** The carrier's own number, exactly as printed. Never validated against a format. */
    @Column(name = "invoice_number", updatable = false, nullable = false)
    private String invoiceNumber;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /** ISO-4217, and never converted: two currencies do not add up. */
    @Column(name = "currency", updatable = false, nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvoiceStatus status = InvoiceStatus.RECEIVED;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "notes")
    private String notes;

    // ------------------------------------------------------------------ V66 (ADR-024 part 2)

    /** 01, 07 or 08. Every pre-V66 row is a factura. */
    @Convert(converter = DocumentType.CodeConverter.class)
    @Column(name = "document_type", updatable = false, nullable = false)
    private DocumentType documentType = DocumentType.INVOICE;

    @Column(name = "series", updatable = false)
    private String series;

    /** Without leading zeros, so 00000123 and 123 are one document. */
    @Column(name = "correlative", updatable = false)
    private String correlative;

    @Column(name = "issuer_ruc", updatable = false)
    private String issuerRuc;

    /** Valor de venta, net of IGV. Null on a document without breakdown. */
    @Column(name = "subtotal_amount")
    private BigDecimal subtotalAmount;

    @Column(name = "igv_rate")
    private BigDecimal igvRate;

    @Column(name = "igv_amount")
    private BigDecimal igvAmount;

    /** The invoice a note corrects; null on an invoice. Same company and carrier by composite FK. */
    @Column(name = "referenced_invoice_id", updatable = false)
    private UUID referencedInvoiceId;

    @Column(name = "detraction_code")
    private String detractionCode;

    @Column(name = "detraction_rate")
    private BigDecimal detractionRate;

    @Column(name = "detraction_amount")
    private BigDecimal detractionAmount;

    @Column(name = "detraction_constancy_number")
    private String detractionConstancyNumber;

    @Column(name = "detraction_constancy_date")
    private LocalDate detractionConstancyDate;

    @Column(name = "retention_rate")
    private BigDecimal retentionRate;

    @Column(name = "retention_amount")
    private BigDecimal retentionAmount;

    @Column(name = "retention_override_reason")
    private String retentionOverrideReason;

    // ------------------------------------------------------------------ V67 (ADR-023 section 2)

    /** invoice_date + terms, set at approval and frozen. Null before approval and on pre-V67 approvals. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays;

    /** What has been paid to the carrier, as reported. Mirrors the sum of its payments. */
    @Column(name = "paid_amount", nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "on_hold_reason")
    private String onHoldReason;

    @Column(name = "scheduled_payment_date")
    private LocalDate scheduledPaymentDate;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    private List<CarrierInvoiceLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /**
     * Optimistic lock. Two people approving the same invoice in the same second is the concurrency
     * case this table has to survive, and the loser gets a stale-write failure rather than a second
     * obligation.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CarrierInvoice() {
    }

    public CarrierInvoice(UUID companyId, UUID carrierId, String invoiceNumber, LocalDate invoiceDate,
            String currency, BigDecimal totalAmount, String externalReference, String notes, UUID actorId) {
        this.companyId = companyId;
        this.carrierId = carrierId;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.externalReference = externalReference;
        this.notes = notes;
        this.receivedAt = OffsetDateTime.now();
        this.status = InvoiceStatus.RECEIVED;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Moves the invoice, refusing anything the transition table does not allow.
     *
     * <p>{@link ConflictException} rather than {@link IllegalStateException}, unlike {@code Trip}:
     * every caller-facing path here is a person clicking a button on an invoice whose state may have
     * moved under them, which is a 409 and not a defect. The service refuses first with a sentence
     * naming both states; this is the backstop.
     */
    public void transitionTo(InvoiceStatus target, UUID actorId) {
        if (status == target) {
            return;
        }
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("An invoice that is " + readable(status) + " cannot become "
                    + readable(target) + ".");
        }
        this.status = target;
        this.updatedBy = actorId;
    }

    /**
     * Replaces the lines, refusing once anybody has decided on the invoice.
     *
     * <p>Editing a line under an approval would change what was authorised without re-authorising
     * it, which is the one thing a freight audit must never allow.
     */
    public void replaceLines(List<CarrierInvoiceLine> replacements, UUID actorId) {
        requireEditable();
        lines.clear();
        int lineNumber = 1;
        for (CarrierInvoiceLine line : replacements) {
            line.attachTo(this, lineNumber++);
            lines.add(line);
        }
        this.updatedBy = actorId;
    }

    public void updateHeader(LocalDate invoiceDate, BigDecimal totalAmount, String externalReference,
            String notes, UUID actorId) {
        requireEditable();
        this.invoiceDate = invoiceDate;
        this.totalAmount = totalAmount;
        this.externalReference = externalReference;
        this.notes = notes;
        this.updatedBy = actorId;
    }

    /**
     * The SUNAT identity and, for a note, the invoice it corrects (V66). Set once, at receipt; the
     * columns are not updatable.
     */
    public void identifyAs(DocumentType documentType, String series, String correlative, String issuerRuc,
            UUID referencedInvoiceId) {
        if (id != null) {
            throw new IllegalStateException("a document's identity is set when it is received");
        }
        if (documentType.isNote() != (referencedInvoiceId != null)) {
            throw new IllegalArgumentException("a note references an invoice and an invoice references nothing");
        }
        this.documentType = documentType;
        this.series = series;
        this.correlative = correlative;
        this.issuerRuc = issuerRuc;
        this.referencedInvoiceId = referencedInvoiceId;
    }

    /**
     * The tax breakdown, all three or none, adding up to the total within one cent
     * ({@code ck_carrier_invoice_tax_breakdown}).
     */
    public void applyBreakdown(BigDecimal subtotalAmount, BigDecimal igvRate, BigDecimal igvAmount) {
        requireEditable();
        boolean none = subtotalAmount == null && igvRate == null && igvAmount == null;
        boolean all = subtotalAmount != null && igvRate != null && igvAmount != null;
        if (!none && !all) {
            throw new IllegalArgumentException("a tax breakdown states subtotal, IGV rate and IGV amount together");
        }
        if (all && !TaxCalculator.breakdownAddsUp(subtotalAmount, igvAmount, totalAmount)) {
            throw new IllegalArgumentException("subtotal + IGV must equal the total within "
                    + TaxCalculator.BREAKDOWN_TOLERANCE);
        }
        this.subtotalAmount = subtotalAmount;
        this.igvRate = igvRate;
        this.igvAmount = igvAmount;
    }

    /** What the computation said, replacing whatever was there (exclusive by construction). */
    public void applyTaxes(TaxCalculator.TaxOutcome outcome) {
        requireEditable();
        TaxCalculator.Detraction detraction = outcome.detraction();
        this.detractionCode = detraction == null ? null : detraction.code();
        this.detractionRate = detraction == null ? null : detraction.rate();
        this.detractionAmount = detraction == null ? null : detraction.amount();
        if (detraction == null) {
            this.detractionConstancyNumber = null;
            this.detractionConstancyDate = null;
        }
        TaxCalculator.Retention retention = outcome.retention();
        this.retentionRate = retention == null ? null : retention.rate();
        this.retentionAmount = retention == null ? null : retention.amount();
        this.retentionOverrideReason = null;
    }

    /**
     * A person replaces the computed retention, with a reason (ADR-024 part 2, item 8: a "buen
     * contribuyente" is not modelled, so an exempt carrier is set to zero by hand).
     */
    public void overrideRetention(BigDecimal amount, String reason) {
        requireEditable();
        if (retentionAmount == null) {
            throw new IllegalArgumentException("no retention applies to this document");
        }
        if (amount == null || amount.signum() < 0 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a retention override states a non-negative amount and a reason");
        }
        this.retentionAmount = amount;
        this.retentionOverrideReason = reason.trim();
    }

    /** The deposit's proof, informed by the ERP or typed by a person; only where a detraction applies. */
    public void recordDetractionConstancy(String number, LocalDate date) {
        if (detractionAmount == null) {
            throw new IllegalArgumentException("no detraction applies to this document");
        }
        this.detractionConstancyNumber = number;
        this.detractionConstancyDate = date;
    }

    // ------------------------------------------------------------------ after the export (V67)

    /**
     * Freezes the due date at approval: {@code invoice_date + terms}. The terms are the ones in force
     * then; a carrier whose terms change later does not move what was already authorised.
     */
    public void fixDueDate(int termsDays) {
        if (termsDays < 0 || termsDays > 365) {
            throw new IllegalArgumentException("payment terms are 0 to 365 days");
        }
        this.paymentTermsDays = termsDays;
        this.dueDate = invoiceDate.plusDays(termsDays);
    }

    /**
     * The payment is planned for a date. From {@code EXPORTED} it becomes {@code SCHEDULED}; an
     * invoice already scheduled or partly paid keeps its status and only the date moves. A held
     * invoice is released first, because leaving a hold needs a reason and a date is not one.
     */
    public void schedule(LocalDate date, UUID actorId) {
        if (date == null) {
            throw new com.ebim.tms.shared.api.InvalidRequestException("A schedule names the planned payment date.");
        }
        requirePayable();
        if (status == InvoiceStatus.ON_HOLD) {
            throw new ConflictException("This invoice is on hold. Release the hold, with a reason, before scheduling it.");
        }
        if (status == InvoiceStatus.EXPORTED) {
            transitionTo(InvoiceStatus.SCHEDULED, actorId);
        }
        this.scheduledPaymentDate = date;
        this.updatedBy = actorId;
    }

    public void placeOnHold(String reason, UUID actorId) {
        String why = requireReason(reason, "A hold says why the payment is stopped.");
        requirePayable();
        transitionTo(InvoiceStatus.ON_HOLD, actorId);
        this.onHoldReason = why;
    }

    /**
     * Lifts the hold. Back to {@code PARTIALLY_PAID} when something was already paid, otherwise
     * {@code SCHEDULED} - the table has no way back to {@code EXPORTED}, and a released payable is one
     * somebody intends to pay.
     */
    public void releaseHold(String reason, UUID actorId) {
        requireReason(reason, "Releasing a hold says why.");
        if (status != InvoiceStatus.ON_HOLD) {
            throw new ConflictException("Only an invoice on hold can be released. This one is " + readable(status)
                    + ".");
        }
        transitionTo(paidAmount.signum() > 0 ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.SCHEDULED, actorId);
        this.onHoldReason = null;
    }

    /**
     * One payment, already checked against the open balance by the caller: {@code PAID} when nothing
     * remains, {@code PARTIALLY_PAID} otherwise. A payment the ERP made on a held invoice ends the hold
     * - the money left regardless, and the status must say what happened.
     */
    public void applyPayment(BigDecimal amount, BigDecimal openBalanceAfter, UUID actorId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("a payment is a positive amount");
        }
        if (openBalanceAfter.signum() < 0) {
            throw new IllegalArgumentException("a payment may not exceed the open balance");
        }
        if (!status.acceptsPayment()) {
            throw new ConflictException("A payment cannot be recorded on an invoice that is " + readable(status) + ".");
        }
        transitionTo(openBalanceAfter.signum() == 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID, actorId);
        this.paidAmount = paidAmount.add(amount);
        this.onHoldReason = null;
    }

    private void requirePayable() {
        if (documentType.isNote()) {
            throw new ConflictException("A credit or debit note adjusts its invoice's balance; it is not paid on its own.");
        }
        if (!status.acceptsPayment()) {
            throw new ConflictException("Only an exported invoice that is not yet paid can be scheduled or held."
                    + " This one is " + readable(status) + ".");
        }
    }

    private static String requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new com.ebim.tms.shared.api.InvalidRequestException(message);
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new com.ebim.tms.shared.api.InvalidRequestException("A reason is at most 500 characters.");
        }
        return trimmed;
    }

    /** +1 or -1: what this document does to what is owed. */
    public int effectiveSign() {
        return documentType.effectiveSign();
    }

    /** What the shipments are compared against: the subtotal when broken down, the total otherwise. */
    public BigDecimal comparableAmount() {
        return subtotalAmount != null ? subtotalAmount : totalAmount;
    }

    private void requireEditable() {
        if (!status.isEditable()) {
            throw new ConflictException("An invoice that is " + readable(status)
                    + " can no longer be edited. Somebody has already decided on it.");
        }
    }

    private static String readable(InvoiceStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public String invoiceNumber() {
        return invoiceNumber;
    }

    public LocalDate invoiceDate() {
        return invoiceDate;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    public InvoiceStatus status() {
        return status;
    }

    public String externalReference() {
        return externalReference;
    }

    public OffsetDateTime receivedAt() {
        return receivedAt;
    }

    public String notes() {
        return notes;
    }

    public DocumentType documentType() {
        return documentType;
    }

    public String series() {
        return series;
    }

    public String correlative() {
        return correlative;
    }

    public String issuerRuc() {
        return issuerRuc;
    }

    public BigDecimal subtotalAmount() {
        return subtotalAmount;
    }

    public BigDecimal igvRate() {
        return igvRate;
    }

    public BigDecimal igvAmount() {
        return igvAmount;
    }

    public UUID referencedInvoiceId() {
        return referencedInvoiceId;
    }

    public String detractionCode() {
        return detractionCode;
    }

    public BigDecimal detractionRate() {
        return detractionRate;
    }

    public BigDecimal detractionAmount() {
        return detractionAmount;
    }

    public String detractionConstancyNumber() {
        return detractionConstancyNumber;
    }

    public LocalDate detractionConstancyDate() {
        return detractionConstancyDate;
    }

    public BigDecimal retentionRate() {
        return retentionRate;
    }

    public BigDecimal retentionAmount() {
        return retentionAmount;
    }

    public String retentionOverrideReason() {
        return retentionOverrideReason;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public Integer paymentTermsDays() {
        return paymentTermsDays;
    }

    public BigDecimal paidAmount() {
        return paidAmount;
    }

    public String onHoldReason() {
        return onHoldReason;
    }

    public LocalDate scheduledPaymentDate() {
        return scheduledPaymentDate;
    }

    public List<CarrierInvoiceLine> lines() {
        return List.copyOf(lines);
    }

    /**
     * Who recorded this invoice - the <em>maker</em>, in maker/checker terms.
     *
     * <p>Exposed so {@code SettlementService} can refuse to let the same person authorise it.
     * Separate permissions make the two authorities <em>separable</em>; they do not make them
     * separated, because one account can hold both. This column is what turns the intent into a
     * rule.
     */
    public UUID createdBy() {
        return createdBy;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
