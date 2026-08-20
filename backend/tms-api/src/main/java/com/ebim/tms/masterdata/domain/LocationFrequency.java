package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A {@link Location}'s service-calendar assignment (migration V15): which {@link Frequency}
 * governs whether it can be serviced/dispatched on a given date, optionally bounded by an
 * effective date range. Deliberately independent of {@link Route#frequencyId()}, which describes
 * a route's own planning cadence, not any one stop's - see the migration header comment and
 * {@code docs/database/DATA_MODEL.md} section 15.
 *
 * <p>Carries its own {@code companyId}, like {@link RouteStop}, because it cross-references two
 * independently company-scoped masters ({@link Location} and {@link Frequency}).
 */
@Entity
@Table(name = "location_frequency")
public class LocationFrequency {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "location_id", updatable = false, nullable = false)
    private UUID locationId;

    @Column(name = "frequency_id", updatable = false, nullable = false)
    private UUID frequencyId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

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

    protected LocationFrequency() {
        // JPA
    }

    public LocationFrequency(UUID companyId, UUID locationId, UUID frequencyId, LocalDate effectiveFrom,
            LocalDate effectiveTo, UUID actorId) {
        this.companyId = companyId;
        this.locationId = locationId;
        this.frequencyId = frequencyId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID locationId() {
        return locationId;
    }

    public UUID frequencyId() {
        return frequencyId;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    public boolean active() {
        return active;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    /** True when {@code date} falls within the (possibly unbounded) effective range. */
    public boolean coversDate(LocalDate date) {
        return (effectiveFrom == null || !date.isBefore(effectiveFrom))
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public void applyChanges(LocalDate effectiveFrom, LocalDate effectiveTo, UUID actorId) {
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
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
