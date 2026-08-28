package com.ebim.tms.fleet.domain;

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
 * A window in which a vehicle or a driver cannot work (migration V42).
 *
 * <p>Exactly one of {@link #vehicleId} and {@link #driverId} is set, which the database enforces
 * through {@code ck_resource_unavailability_one_resource} and the two factory methods here make
 * unrepresentable in Java. There is no public constructor for that reason: a block on nothing, or
 * on both, is not a thing this type can hold.
 *
 * <p>Absolute instants, not a weekly rule. "Off next Tuesday" is this; "off on Tuesdays" is
 * {@link DriverShift}. The two sentences use the same words and mean different things, and keeping
 * them in one table is how a holiday becomes a permanent absence.
 */
@Entity
@Table(name = "resource_unavailability")
public class ResourceUnavailability {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "driver_id", updatable = false)
    private UUID driverId;

    @Column(name = "vehicle_id", updatable = false)
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private UnavailabilityReason reason;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected ResourceUnavailability() {
    }

    private ResourceUnavailability(UUID companyId, UUID driverId, UUID vehicleId, UnavailabilityReason reason,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String notes, UUID createdBy) {
        this.companyId = companyId;
        this.driverId = driverId;
        this.vehicleId = vehicleId;
        this.reason = reason;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.notes = notes;
        this.createdBy = createdBy;
        assertWindow();
        assertReasonFitsResource();
    }

    public static ResourceUnavailability forVehicle(UUID companyId, UUID vehicleId, UnavailabilityReason reason,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String notes, UUID createdBy) {
        return new ResourceUnavailability(companyId, null, vehicleId, reason, startsAt, endsAt, notes, createdBy);
    }

    public static ResourceUnavailability forDriver(UUID companyId, UUID driverId, UnavailabilityReason reason,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String notes, UUID createdBy) {
        return new ResourceUnavailability(companyId, driverId, null, reason, startsAt, endsAt, notes, createdBy);
    }

    /**
     * Moves the window. The resource never changes - a block that could be moved from one truck to
     * another would let a correction to today's workshop booking silently free yesterday's.
     */
    public void reschedule(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        assertWindow();
    }

    public void describe(UnavailabilityReason reason, String notes) {
        this.reason = reason;
        this.notes = notes;
        assertReasonFitsResource();
    }

    public boolean isVehicleBlock() {
        return vehicleId != null;
    }

    /** Half-open, matching {@code tstzrange(starts_at, ends_at)}: a block ending at 10:00 frees 10:00. */
    public boolean coversInstant(OffsetDateTime at) {
        return !at.isBefore(startsAt) && at.isBefore(endsAt);
    }

    private void assertWindow() {
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalStateException("an unavailability window must end after it starts");
        }
    }

    private void assertReasonFitsResource() {
        boolean fits = isVehicleBlock() ? reason.appliesToVehicle() : reason.appliesToDriver();
        if (!fits) {
            throw new IllegalStateException(reason + " does not describe a "
                    + (isVehicleBlock() ? "vehicle" : "driver"));
        }
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID driverId() {
        return driverId;
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public UnavailabilityReason reason() {
        return reason;
    }

    public OffsetDateTime startsAt() {
        return startsAt;
    }

    public OffsetDateTime endsAt() {
        return endsAt;
    }

    public String notes() {
        return notes;
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
}
