package com.ebim.tms.fleet.domain;

import com.ebim.tms.shared.reference.ComplianceEnforcement;
import com.ebim.tms.shared.reference.FleetDocumentType;
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
 * A company's decision about one document type (migration V72): mandatory or not, and whether an
 * expired one blocks or warns. A type without a row follows {@link FleetDocumentType}'s defaults.
 */
@Entity
@Table(name = "fleet_document_policy")
public class FleetDocumentPolicy {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", updatable = false, nullable = false)
    private FleetDocumentType documentType;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement", nullable = false)
    private ComplianceEnforcement enforcement;

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

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FleetDocumentPolicy() {
        // JPA
    }

    public FleetDocumentPolicy(UUID companyId, FleetDocumentType documentType, boolean mandatory,
            ComplianceEnforcement enforcement, UUID actorId) {
        this.companyId = companyId;
        this.documentType = documentType;
        this.mandatory = mandatory;
        this.enforcement = enforcement;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /** @return whether anything changed, so an unchanged save is not audited */
    public boolean change(boolean mandatory, ComplianceEnforcement enforcement, UUID actorId) {
        if (this.mandatory == mandatory && this.enforcement == enforcement) {
            return false;
        }
        this.mandatory = mandatory;
        this.enforcement = enforcement;
        this.updatedBy = actorId;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public FleetDocumentType documentType() {
        return documentType;
    }

    public boolean mandatory() {
        return mandatory;
    }

    public ComplianceEnforcement enforcement() {
        return enforcement;
    }
}
