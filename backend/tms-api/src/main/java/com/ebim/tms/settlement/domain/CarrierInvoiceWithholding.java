package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.tax.ComputedWithholding;
import com.ebim.tms.settlement.domain.tax.WithholdingBase;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One withholding of a carrier document (V66, ADR-027): Peru's detraction or retention, Ecuador's
 * income-tax or VAT withholding. Computed by TMS when the document is received, overridable with a
 * reason where the kind allows it, never paid by TMS (D-01).
 *
 * <p>The figures are frozen once written - {@code tr_carrier_invoice_withholding_protect} refuses any
 * other change - and only the certificate (the detraction constancy, Ecuador's comprobante de
 * retención) is recorded later, when the ERP or a person reports it.
 */
@Entity
@Table(name = "carrier_invoice_withholding")
public class CarrierInvoiceWithholding {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrier_invoice_id", nullable = false, updatable = false)
    private CarrierInvoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", updatable = false, nullable = false)
    private WithholdingKind kind;

    @Column(name = "code", updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "base", updatable = false, nullable = false)
    private WithholdingBase base;

    @Column(name = "base_amount", updatable = false, nullable = false)
    private BigDecimal baseAmount;

    @Column(name = "rate", updatable = false, nullable = false)
    private BigDecimal rate;

    @Column(name = "computed_amount", updatable = false, nullable = false)
    private BigDecimal computedAmount;

    /** Not {@code updatable = false}: set at receipt, and an override at receipt replaces it before the insert. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "certificate_number")
    private String certificateNumber;

    @Column(name = "certificate_date")
    private LocalDate certificateDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CarrierInvoiceWithholding() {
    }

    CarrierInvoiceWithholding(CarrierInvoice invoice, ComputedWithholding computed) {
        this.invoice = invoice;
        this.companyId = invoice.companyId();
        this.kind = computed.kind();
        this.code = computed.code();
        this.base = computed.base();
        this.baseAmount = computed.baseAmount();
        this.rate = computed.rate();
        this.computedAmount = computed.amount();
        this.amount = computed.amount();
    }

    /** A person replaces the computed amount, with a reason; only where the kind allows it. */
    void override(BigDecimal newAmount, String reason) {
        if (id != null) {
            throw new IllegalStateException("a withholding is overridden when its document is received");
        }
        if (!kind.overridable()) {
            throw new IllegalArgumentException(kind.label() + " cannot be changed by hand");
        }
        if (newAmount == null || newAmount.signum() < 0 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a withholding override states a non-negative amount and a reason");
        }
        if (newAmount.compareTo(baseAmount) > 0) {
            throw new IllegalArgumentException("a withholding cannot exceed its base of " + baseAmount.toPlainString());
        }
        this.amount = newAmount;
        this.overrideReason = reason.trim();
    }

    /** The proof of the deposit, informed by the ERP or typed by a person. */
    void recordCertificate(String number, LocalDate date) {
        if (date != null && number == null) {
            throw new IllegalArgumentException("a certificate date needs its number");
        }
        this.certificateNumber = number;
        this.certificateDate = date;
    }

    public UUID id() {
        return id;
    }

    public WithholdingKind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public WithholdingBase base() {
        return base;
    }

    public BigDecimal baseAmount() {
        return baseAmount;
    }

    public BigDecimal rate() {
        return rate;
    }

    public BigDecimal computedAmount() {
        return computedAmount;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String overrideReason() {
        return overrideReason;
    }

    public String certificateNumber() {
        return certificateNumber;
    }

    public LocalDate certificateDate() {
        return certificateDate;
    }
}
