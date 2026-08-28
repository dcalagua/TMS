package com.ebim.tms.settlement.domain;

import com.ebim.tms.shared.api.ConflictException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
