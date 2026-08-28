package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Somebody authorised - or refused - an obligation (migration V46).
 *
 * <p><b>Append-only.</b> An approval reversed is a second row, never an edit. A record of a decision
 * that can be edited is not a record, which is why V46 grants {@code tms_app} SELECT and INSERT on
 * this table and nothing else.
 *
 * <p>{@code decidedBy} is NOT NULL and references a real {@code app_user}. That is structural rather
 * than a service check: <b>a machine cannot approve an expenditure.</b> {@code requireAppUserId}
 * refuses machine principals by design (debt D4), no system actor exists to satisfy this column, and
 * none was invented to make settlement automatable.
 */
@Entity
@Table(name = "settlement_approval")
public class SettlementApproval {

    public enum Decision { APPROVED, REJECTED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "carrier_invoice_id", updatable = false, nullable = false)
    private UUID carrierInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", updatable = false, nullable = false)
    private Decision decision;

    @Column(name = "decided_by", updatable = false, nullable = false)
    private UUID decidedBy;

    @Column(name = "decided_at", updatable = false, nullable = false)
    private OffsetDateTime decidedAt;

    @Column(name = "comment", updatable = false)
    private String comment;

    protected SettlementApproval() {
    }

    public SettlementApproval(UUID companyId, UUID carrierInvoiceId, Decision decision, UUID decidedBy,
            String comment) {
        if (decidedBy == null) {
            // Mirrors the NOT NULL. Stated here too so the refusal names the reason rather than
            // arriving as a constraint violation nobody can read.
            throw new IllegalArgumentException("an expenditure is authorised by a person, never by a machine");
        }
        if (decision == Decision.REJECTED && (comment == null || comment.isBlank())) {
            // Mirrors ck_settlement_approval_rejection_explains. Refusing to pay without saying why
            // is not a decision a carrier can answer.
            throw new IllegalArgumentException("a rejection must say why");
        }
        this.companyId = companyId;
        this.carrierInvoiceId = carrierInvoiceId;
        this.decision = decision;
        this.decidedBy = decidedBy;
        this.decidedAt = OffsetDateTime.now();
        this.comment = comment;
    }

    public UUID id() {
        return id;
    }

    public UUID carrierInvoiceId() {
        return carrierInvoiceId;
    }

    public Decision decision() {
        return decision;
    }

    public UUID decidedBy() {
        return decidedBy;
    }

    public OffsetDateTime decidedAt() {
        return decidedAt;
    }

    public String comment() {
        return comment;
    }
}
