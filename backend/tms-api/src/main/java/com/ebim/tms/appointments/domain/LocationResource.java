package com.ebim.tms.appointments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A dock, door, bay or yard slot that takes <b>one vehicle at a time</b> (migration V41).
 *
 * <p>Six doors are six rows. That is what makes "no double booking" a database constraint rather
 * than a service check - PostgreSQL can refuse two overlapping ranges on one key and cannot refuse
 * "more than N overlapping", so a capacity column would move the invariant back into application
 * code, which is precisely where the spreadsheet this replaces already fails.
 */
@Entity
@Table(name = "location_resource")
public class LocationResource {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "location_id", updatable = false, nullable = false)
    private UUID locationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceType resourceType = ResourceType.DOCK;

    @Column(name = "default_slot_minutes", nullable = false)
    private int defaultSlotMinutes = 60;

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

    protected LocationResource() {}

    public LocationResource(UUID companyId, UUID locationId, String code, String name, ResourceType resourceType,
            int defaultSlotMinutes, UUID actorId) {
        this.companyId = companyId;
        this.locationId = locationId;
        this.code = code;
        this.name = name;
        this.resourceType = resourceType;
        this.defaultSlotMinutes = defaultSlotMinutes;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public void applyChanges(String code, String name, ResourceType resourceType, int defaultSlotMinutes,
            UUID actorId) {
        this.code = code;
        this.name = name;
        this.resourceType = resourceType;
        this.defaultSlotMinutes = defaultSlotMinutes;
        this.updatedBy = actorId;
    }

    /**
     * Deactivates the door.
     *
     * <p>Existing bookings are deliberately left alone: a door taken out of service still has
     * trucks booked against it, and silently cancelling them would strand vehicles that are already
     * on the road. The service refuses <em>new</em> bookings and a person deals with the ones that
     * exist - which is what a site actually does when a leveller breaks.
     */
    public void deactivate(UUID actorId) {
        this.active = false;
        this.updatedBy = actorId;
    }

    public void activate(UUID actorId) {
        this.active = true;
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

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public ResourceType resourceType() {
        return resourceType;
    }

    public int defaultSlotMinutes() {
        return defaultSlotMinutes;
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
}
