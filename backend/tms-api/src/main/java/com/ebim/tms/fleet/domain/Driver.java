package com.ebim.tms.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The person who drives (migration V26): company-scoped, optionally employed through a
 * {@link Carrier}, always carrying an identity document and a licence number.
 *
 * <p>{@code documentType}/{@code documentNumber} are a flexible free-text pair rather than an
 * enum, for exactly the reason {@link Carrier}'s {@code taxIdType}/{@code taxIdValue} are - see
 * the V26 migration comment.
 *
 * <p>{@code licenseExpiresOn} is the last day the licence is valid, <em>inclusive</em>, and may be
 * null when a company does not track it. This entity deliberately draws no conclusion from it: what
 * a given expiry date means is
 * {@link com.ebim.tms.shared.reference.DriverLicenseStatus}'s single answer, and it needs a day to
 * judge against that only the caller knows - the same split {@link Vehicle} keeps with
 * {@code com.ebim.tms.fleet.application.EffectiveCapacityResolver}.
 */
@Entity
@Table(name = "driver")
public class Driver {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @Column(name = "document_number", nullable = false)
    private String documentNumber;

    @Column(name = "phone")
    private String phone;

    @Column(name = "license_number", nullable = false)
    private String licenseNumber;

    @Column(name = "license_category")
    private String licenseCategory;

    @Column(name = "license_expires_on")
    private LocalDate licenseExpiresOn;

    @Column(name = "carrier_id")
    private UUID carrierId;

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

    protected Driver() {
        // JPA
    }

    public Driver(UUID companyId, String code, String firstName, String lastName, String documentType,
            String documentNumber, String phone, String licenseNumber, String licenseCategory,
            LocalDate licenseExpiresOn, UUID carrierId, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.firstName = firstName;
        this.lastName = lastName;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.phone = phone;
        this.licenseNumber = licenseNumber;
        this.licenseCategory = licenseCategory;
        this.licenseExpiresOn = licenseExpiresOn;
        this.carrierId = carrierId;
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

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    /**
     * The label a human reads, composed rather than stored so the two halves can never disagree
     * with it. "Last, first" is the dispatch-list order: a manifest is read by surname.
     */
    public String fullName() {
        return lastName + ", " + firstName;
    }

    public String documentType() {
        return documentType;
    }

    public String documentNumber() {
        return documentNumber;
    }

    public String phone() {
        return phone;
    }

    public String licenseNumber() {
        return licenseNumber;
    }

    public String licenseCategory() {
        return licenseCategory;
    }

    public LocalDate licenseExpiresOn() {
        return licenseExpiresOn;
    }

    public UUID carrierId() {
        return carrierId;
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

    public void applyChanges(String code, String firstName, String lastName, String documentType,
            String documentNumber, String phone, String licenseNumber, String licenseCategory,
            LocalDate licenseExpiresOn, UUID carrierId, UUID actorId) {
        this.code = code;
        this.firstName = firstName;
        this.lastName = lastName;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.phone = phone;
        this.licenseNumber = licenseNumber;
        this.licenseCategory = licenseCategory;
        this.licenseExpiresOn = licenseExpiresOn;
        this.carrierId = carrierId;
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
