package com.ebim.tms.fleet.domain;

import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One compliance paper of one vehicle or one driver (migration V72).
 *
 * <p>The subject never changes after creation - a registration does not move from one truck to
 * another; a new truck gets a new paper. Its status is never stored: {@link #statusOn} derives it.
 */
@Entity
@Table(name = "fleet_document")
public class FleetDocument {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", updatable = false, nullable = false)
    private FleetDocumentSubject subjectType;

    @Column(name = "vehicle_id", updatable = false)
    private UUID vehicleId;

    @Column(name = "driver_id", updatable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private FleetDocumentType documentType;

    @Column(name = "document_number")
    private String documentNumber;

    @Column(name = "issuer")
    private String issuer;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "notes")
    private String notes;

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

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FleetDocument() {
        // JPA
    }

    public FleetDocument(UUID companyId, FleetDocumentSubject subjectType, UUID subjectId,
            FleetDocumentType documentType, String documentNumber, String issuer, LocalDate issuedOn,
            LocalDate expiresOn, String notes, UUID actorId) {
        if (documentType.subject() != subjectType) {
            throw new IllegalArgumentException(documentType + " is not a document of a " + subjectType);
        }
        this.companyId = companyId;
        this.subjectType = subjectType;
        this.vehicleId = subjectType == FleetDocumentSubject.VEHICLE ? subjectId : null;
        this.driverId = subjectType == FleetDocumentSubject.DRIVER ? subjectId : null;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.issuer = issuer;
        this.issuedOn = issuedOn;
        this.expiresOn = expiresOn;
        this.notes = notes;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /** The type may change within the same subject (a paper recorded under the wrong name). */
    public void applyChanges(FleetDocumentType documentType, String documentNumber, String issuer,
            LocalDate issuedOn, LocalDate expiresOn, String notes, UUID actorId) {
        if (documentType.subject() != subjectType) {
            throw new IllegalArgumentException(documentType + " is not a document of a " + subjectType);
        }
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.issuer = issuer;
        this.issuedOn = issuedOn;
        this.expiresOn = expiresOn;
        this.notes = notes;
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

    public FleetDocumentStatus statusOn(LocalDate on, int alertDays) {
        return FleetDocumentStatus.of(expiresOn, on, alertDays);
    }

    public UUID subjectId() {
        return subjectType == FleetDocumentSubject.VEHICLE ? vehicleId : driverId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public FleetDocumentSubject subjectType() {
        return subjectType;
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public UUID driverId() {
        return driverId;
    }

    public FleetDocumentType documentType() {
        return documentType;
    }

    public String documentNumber() {
        return documentNumber;
    }

    public String issuer() {
        return issuer;
    }

    public LocalDate issuedOn() {
        return issuedOn;
    }

    public LocalDate expiresOn() {
        return expiresOn;
    }

    public String notes() {
        return notes;
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

    public long version() {
        return version;
    }
}
