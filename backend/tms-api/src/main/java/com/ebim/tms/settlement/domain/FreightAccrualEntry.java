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
 * One movement of a freight accrual (migration V65). Append-only three times over: no setter here,
 * {@code @Immutable} so Hibernate never issues an UPDATE, and in the database both the grant
 * ({@code SELECT, INSERT}) and {@code tr_freight_accrual_entry_append_only}.
 */
@Entity
@Immutable
@Table(name = "freight_accrual_entry")
public class FreightAccrualEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "accrual_id", updatable = false, nullable = false)
    private UUID accrualId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", updatable = false, nullable = false)
    private AccrualEntryType entryType;

    @Column(name = "amount", updatable = false, nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "period", updatable = false, nullable = false)
    private LocalDate period;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", updatable = false, nullable = false)
    private AccrualSourceType sourceType;

    @Column(name = "source_key", updatable = false, nullable = false)
    private String sourceKey;

    @Column(name = "carrier_invoice_id", updatable = false)
    private UUID carrierInvoiceId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected FreightAccrualEntry() {
        // JPA
    }

    public FreightAccrualEntry(FreightAccrual accrual, AccrualEntryType entryType, BigDecimal amount,
            LocalDate period, AccrualSourceType sourceType, String sourceKey, UUID carrierInvoiceId,
            String reason, UUID createdBy) {
        if (!entryType.accepts(amount)) {
            // Mirrors ck_freight_accrual_entry_sign, so the refusal names the rule.
            throw new IllegalArgumentException(entryType + " cannot carry " + amount.toPlainString());
        }
        boolean needsReason = sourceType == AccrualSourceType.MANUAL || entryType == AccrualEntryType.REVERSE;
        if (needsReason && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a manual movement or a reversal says why");
        }
        this.companyId = accrual.companyId();
        this.accrualId = accrual.id();
        this.entryType = entryType;
        this.amount = amount;
        this.period = period.withDayOfMonth(1);
        this.sourceType = sourceType;
        this.sourceKey = sourceKey;
        this.carrierInvoiceId = carrierInvoiceId;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public UUID id() {
        return id;
    }

    public UUID accrualId() {
        return accrualId;
    }

    public AccrualEntryType entryType() {
        return entryType;
    }

    public BigDecimal amount() {
        return amount;
    }

    public LocalDate period() {
        return period;
    }

    public AccrualSourceType sourceType() {
        return sourceType;
    }

    public String sourceKey() {
        return sourceKey;
    }

    public UUID carrierInvoiceId() {
        return carrierInvoiceId;
    }

    public String reason() {
        return reason;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
