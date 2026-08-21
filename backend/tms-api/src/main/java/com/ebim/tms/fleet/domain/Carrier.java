package com.ebim.tms.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A transport provider (third-party or the company's own fleet operator), migration V9.
 *
 * <p>{@code taxIdType}/{@code taxIdValue} are a flexible free-text pair rather than an enum -
 * see the V9 migration comment on {@code tms.carrier.tax_id_type} for why.
 *
 * <p>{@code createdAt}/{@code updatedAt} are read back from the database rather than computed
 * here, for the same reason as {@link com.ebim.tms.masterdata.domain.Zone}: the
 * {@code updated_at} trigger is authoritative.
 */
@Entity
@Table(name = "carrier")
public class Carrier {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "tax_id_type", nullable = false)
    private String taxIdType;

    @Column(name = "tax_id_value", nullable = false)
    private String taxIdValue;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "external_reference")
    private String externalReference;

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

    protected Carrier() {
        // JPA
    }

    public Carrier(UUID companyId, String code, String businessName, String taxIdType, String taxIdValue,
            String contactName, String phone, String email, String externalReference, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.businessName = businessName;
        this.taxIdType = taxIdType;
        this.taxIdValue = taxIdValue;
        this.contactName = contactName;
        this.phone = phone;
        this.email = email;
        this.externalReference = externalReference;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String businessName() {
        return businessName;
    }

    public String taxIdType() {
        return taxIdType;
    }

    public String taxIdValue() {
        return taxIdValue;
    }

    public String contactName() {
        return contactName;
    }

    public String phone() {
        return phone;
    }

    public String email() {
        return email;
    }

    public String externalReference() {
        return externalReference;
    }

    public boolean active() {
        return active;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public void applyChanges(String code, String businessName, String taxIdType, String taxIdValue,
            String contactName, String phone, String email, String externalReference, UUID actorId) {
        this.code = code;
        this.businessName = businessName;
        this.taxIdType = taxIdType;
        this.taxIdValue = taxIdValue;
        this.contactName = contactName;
        this.phone = phone;
        this.email = email;
        this.externalReference = externalReference;
        this.updatedBy = actorId;
    }

    public void activate(UUID actorId) {
        this.active = true;
        this.updatedBy = actorId;
    }

    public void deactivate(UUID actorId) {
        this.active = false;
        this.updatedBy = actorId;
    }
}
