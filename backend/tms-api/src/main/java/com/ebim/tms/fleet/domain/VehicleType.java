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
 * A class of vehicle and its default capacities/physical characteristics (migration V9).
 *
 * <p>Units are explicit in every accessor name - kilograms, cubic meters, meters, Celsius -
 * matching the column names in the migration; the step brief is explicit that kilograms/tons
 * or m3/cm3 must never be mixed implicitly.
 *
 * <p>A {@link com.ebim.tms.fleet.domain.Vehicle} may override {@code maxWeightKg}/
 * {@code maxVolumeM3}/{@code maxPallets} individually; see
 * {@code com.ebim.tms.fleet.application.EffectiveCapacityResolver} for the resolution rule
 * this entity does not implement itself (a stateless cross-aggregate calculation, not a fact
 * this entity owns).
 */
@Entity
@Table(name = "vehicle_type")
public class VehicleType {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "max_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "max_volume_m3", nullable = false, precision = 10, scale = 3)
    private BigDecimal maxVolumeM3;

    @Column(name = "max_pallets", nullable = false)
    private int maxPallets;

    @Column(name = "length_m", precision = 6, scale = 2)
    private BigDecimal lengthM;

    @Column(name = "width_m", precision = 6, scale = 2)
    private BigDecimal widthM;

    @Column(name = "height_m", precision = 6, scale = 2)
    private BigDecimal heightM;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_type")
    private VehicleBodyType bodyType;

    @Column(name = "temperature_controlled", nullable = false)
    private boolean temperatureControlled;

    @Column(name = "min_temperature_celsius", precision = 5, scale = 2)
    private BigDecimal minTemperatureCelsius;

    @Column(name = "max_temperature_celsius", precision = 5, scale = 2)
    private BigDecimal maxTemperatureCelsius;

    @Column(name = "axles")
    private Integer axles;

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

    protected VehicleType() {
        // JPA
    }

    public VehicleType(UUID companyId, String code, String name, BigDecimal maxWeightKg, BigDecimal maxVolumeM3,
            int maxPallets, BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM, VehicleBodyType bodyType,
            boolean temperatureControlled, BigDecimal minTemperatureCelsius, BigDecimal maxTemperatureCelsius,
            Integer axles, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.maxWeightKg = maxWeightKg;
        this.maxVolumeM3 = maxVolumeM3;
        this.maxPallets = maxPallets;
        this.lengthM = lengthM;
        this.widthM = widthM;
        this.heightM = heightM;
        this.bodyType = bodyType;
        this.temperatureControlled = temperatureControlled;
        this.minTemperatureCelsius = minTemperatureCelsius;
        this.maxTemperatureCelsius = maxTemperatureCelsius;
        this.axles = axles;
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

    public String name() {
        return name;
    }

    public BigDecimal maxWeightKg() {
        return maxWeightKg;
    }

    public BigDecimal maxVolumeM3() {
        return maxVolumeM3;
    }

    public int maxPallets() {
        return maxPallets;
    }

    public BigDecimal lengthM() {
        return lengthM;
    }

    public BigDecimal widthM() {
        return widthM;
    }

    public BigDecimal heightM() {
        return heightM;
    }

    public VehicleBodyType bodyType() {
        return bodyType;
    }

    public boolean temperatureControlled() {
        return temperatureControlled;
    }

    public BigDecimal minTemperatureCelsius() {
        return minTemperatureCelsius;
    }

    public BigDecimal maxTemperatureCelsius() {
        return maxTemperatureCelsius;
    }

    public Integer axles() {
        return axles;
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

    public void applyChanges(String code, String name, BigDecimal maxWeightKg, BigDecimal maxVolumeM3,
            int maxPallets, BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM, VehicleBodyType bodyType,
            boolean temperatureControlled, BigDecimal minTemperatureCelsius, BigDecimal maxTemperatureCelsius,
            Integer axles, UUID actorId) {
        this.code = code;
        this.name = name;
        this.maxWeightKg = maxWeightKg;
        this.maxVolumeM3 = maxVolumeM3;
        this.maxPallets = maxPallets;
        this.lengthM = lengthM;
        this.widthM = widthM;
        this.heightM = heightM;
        this.bodyType = bodyType;
        this.temperatureControlled = temperatureControlled;
        this.minTemperatureCelsius = minTemperatureCelsius;
        this.maxTemperatureCelsius = maxTemperatureCelsius;
        this.axles = axles;
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
