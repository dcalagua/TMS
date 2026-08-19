package com.ebim.tms.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A physical vehicle (migration V9): company-scoped, optionally operated by a {@link Carrier},
 * always assigned a {@link VehicleType}.
 *
 * <p>{@code maxWeightOverrideKg}/{@code maxVolumeOverrideM3}/{@code maxPalletsOverride} are
 * independently optional - a vehicle may override none, one, or all three of its type's
 * defaults. Resolving which value actually applies is
 * {@code com.ebim.tms.fleet.application.EffectiveCapacityResolver}'s job, not this entity's: it
 * needs the referenced {@link VehicleType} to resolve a fallback, and this entity does not hold
 * a JPA association to it (same reasoning as {@link com.ebim.tms.masterdata.domain.Destination}
 * not navigating to its zone - see that entity's class comment).
 *
 * <p>{@code availabilityStatus} is a current-state flag (available / in maintenance / out of
 * service), not a scheduling calendar - live tracking and telematics are deferred by decision.
 */
@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "license_plate", nullable = false)
    private String licensePlate;

    @Column(name = "carrier_id")
    private UUID carrierId;

    @Column(name = "vehicle_type_id", nullable = false)
    private UUID vehicleTypeId;

    @Column(name = "max_weight_override_kg", precision = 10, scale = 2)
    private BigDecimal maxWeightOverrideKg;

    @Column(name = "max_volume_override_m3", precision = 10, scale = 3)
    private BigDecimal maxVolumeOverrideM3;

    @Column(name = "max_pallets_override")
    private Integer maxPalletsOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false)
    private VehicleAvailabilityStatus availabilityStatus;

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

    protected Vehicle() {
        // JPA
    }

    public Vehicle(UUID companyId, String code, String licensePlate, UUID carrierId, UUID vehicleTypeId,
            BigDecimal maxWeightOverrideKg, BigDecimal maxVolumeOverrideM3, Integer maxPalletsOverride,
            VehicleAvailabilityStatus availabilityStatus, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.licensePlate = licensePlate;
        this.carrierId = carrierId;
        this.vehicleTypeId = vehicleTypeId;
        this.maxWeightOverrideKg = maxWeightOverrideKg;
        this.maxVolumeOverrideM3 = maxVolumeOverrideM3;
        this.maxPalletsOverride = maxPalletsOverride;
        this.availabilityStatus = availabilityStatus;
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

    public String licensePlate() {
        return licensePlate;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public UUID vehicleTypeId() {
        return vehicleTypeId;
    }

    public BigDecimal maxWeightOverrideKg() {
        return maxWeightOverrideKg;
    }

    public BigDecimal maxVolumeOverrideM3() {
        return maxVolumeOverrideM3;
    }

    public Integer maxPalletsOverride() {
        return maxPalletsOverride;
    }

    public VehicleAvailabilityStatus availabilityStatus() {
        return availabilityStatus;
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

    public void applyChanges(String code, String licensePlate, UUID carrierId, UUID vehicleTypeId,
            BigDecimal maxWeightOverrideKg, BigDecimal maxVolumeOverrideM3, Integer maxPalletsOverride,
            VehicleAvailabilityStatus availabilityStatus, UUID actorId) {
        this.code = code;
        this.licensePlate = licensePlate;
        this.carrierId = carrierId;
        this.vehicleTypeId = vehicleTypeId;
        this.maxWeightOverrideKg = maxWeightOverrideKg;
        this.maxVolumeOverrideM3 = maxVolumeOverrideM3;
        this.maxPalletsOverride = maxPalletsOverride;
        this.availabilityStatus = availabilityStatus;
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
