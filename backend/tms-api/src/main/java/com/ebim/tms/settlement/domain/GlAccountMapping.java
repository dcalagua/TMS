package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Which account a concept is booked to in the ERP (migration V65, ADR-023). Retired, never deleted:
 * a past period's export named it.
 */
@Entity
@Table(name = "gl_account_mapping")
public class GlAccountMapping {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "concept", updatable = false, nullable = false)
    private GlConcept concept;

    /**
     * The withholding a {@link GlConcept#WITHHOLDING_PAYABLE} mapping books (ADR-027); null = every
     * kind without its own mapping, and always null on another concept.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "withholding_kind", updatable = false)
    private com.ebim.tms.settlement.domain.tax.WithholdingKind withholdingKind;

    @Column(name = "account_code", nullable = false)
    private String accountCode;

    @Column(name = "cost_center_code")
    private String costCenterCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", updatable = false, nullable = false)
    private GlScopeType scopeType;

    @Column(name = "scope_id", updatable = false)
    private UUID scopeId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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

    protected GlAccountMapping() {
        // JPA
    }

    public GlAccountMapping(UUID companyId, GlConcept concept, GlScopeType scopeType, UUID scopeId,
            String accountCode, String costCenterCode, UUID actorId) {
        this(companyId, concept, null, scopeType, scopeId, accountCode, costCenterCode, actorId);
    }

    public GlAccountMapping(UUID companyId, GlConcept concept,
            com.ebim.tms.settlement.domain.tax.WithholdingKind withholdingKind, GlScopeType scopeType, UUID scopeId,
            String accountCode, String costCenterCode, UUID actorId) {
        if (withholdingKind != null && concept != GlConcept.WITHHOLDING_PAYABLE) {
            // Mirrors ck_gl_account_mapping_withholding_kind.
            throw new IllegalArgumentException("only a WITHHOLDING_PAYABLE mapping names a withholding kind");
        }
        this.withholdingKind = withholdingKind;
        if ((scopeType == GlScopeType.COMPANY) != (scopeId == null)) {
            // Mirrors ck_gl_account_mapping_scope_id.
            throw new IllegalArgumentException("a COMPANY mapping names no scope; ORIGIN and ROUTE name one");
        }
        this.companyId = companyId;
        this.concept = concept;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.accountCode = accountCode;
        this.costCenterCode = costCenterCode;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public void change(String accountCode, String costCenterCode, UUID actorId) {
        requireActive();
        this.accountCode = accountCode;
        this.costCenterCode = costCenterCode;
        this.updatedBy = actorId;
    }

    public void deactivate(UUID actorId) {
        this.active = false;
        this.updatedBy = actorId;
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("a retired mapping is not changed; create a new one");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public GlConcept concept() {
        return concept;
    }

    public com.ebim.tms.settlement.domain.tax.WithholdingKind withholdingKind() {
        return withholdingKind;
    }

    public String accountCode() {
        return accountCode;
    }

    public String costCenterCode() {
        return costCenterCode;
    }

    public GlScopeType scopeType() {
        return scopeType;
    }

    public UUID scopeId() {
        return scopeId;
    }

    public boolean active() {
        return active;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
