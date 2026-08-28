package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * How far this company will let an invoice differ before a person has to look (migration V46).
 *
 * <p>One active policy per scope, enforced by two partial unique indexes: a company-wide one, and
 * an optional per-carrier one that outranks it. Two active policies at the same scope would make
 * "what is the tolerance" a question with two answers.
 */
@Entity
@Table(name = "tolerance_policy")
public class TolerancePolicy {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    /** Null is the company-wide default; a carrier-specific row outranks it. */
    @Column(name = "carrier_id", updatable = false)
    private UUID carrierId;

    @Column(name = "absolute_amount")
    private BigDecimal absoluteAmount;

    @Column(name = "percentage")
    private BigDecimal percentage;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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

    protected TolerancePolicy() {
    }

    public TolerancePolicy(UUID companyId, UUID carrierId, BigDecimal absoluteAmount, BigDecimal percentage,
            String currency, UUID actorId) {
        this.companyId = companyId;
        this.carrierId = carrierId;
        this.absoluteAmount = absoluteAmount;
        this.percentage = percentage;
        this.currency = currency;
        this.createdBy = actorId;
        this.updatedBy = actorId;
        assertHasABound();
    }

    public void update(BigDecimal absoluteAmount, BigDecimal percentage, String currency, UUID actorId) {
        this.absoluteAmount = absoluteAmount;
        this.percentage = percentage;
        this.currency = currency;
        this.updatedBy = actorId;
        assertHasABound();
    }

    public void deactivate(UUID actorId) {
        this.active = false;
        this.updatedBy = actorId;
    }

    private void assertHasABound() {
        if (absoluteAmount == null && percentage == null) {
            throw new IllegalArgumentException("a tolerance policy states an absolute bound, a percentage, or both");
        }
        if (absoluteAmount != null && currency == null) {
            throw new IllegalArgumentException("an absolute tolerance is a sum of money and needs its currency");
        }
    }

    /** The value object the matcher uses. Kept apart so the arithmetic needs no entity. */
    public Tolerance toTolerance() {
        return new Tolerance(absoluteAmount, percentage);
    }

    public UUID id() {
        return id;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public BigDecimal absoluteAmount() {
        return absoluteAmount;
    }

    public BigDecimal percentage() {
        return percentage;
    }

    public String currency() {
        return currency;
    }

    public boolean active() {
        return active;
    }
}
