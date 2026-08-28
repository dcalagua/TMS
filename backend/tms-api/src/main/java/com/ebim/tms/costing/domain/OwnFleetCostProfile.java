package com.ebim.tms.costing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * What running one of our own trucks is modelled to cost, between two dates (migration V48).
 *
 * <p>The own-fleet counterpart of {@code rates.RateCard}, and deliberately not
 * the same table: a rate card is a commercial agreement with a carrier and this is a finance model
 * of our own operation. They are configured by different people, they change for different reasons,
 * and the number each produces means something different - see
 * {@link com.ebim.tms.shared.reference.TransportCostNature}.
 *
 * <p>Carries no arithmetic. Turning a profile plus a trip into money is
 * {@link OwnFleetCostCalculator}'s job, a pure function over {@link #rates()}, which is what lets
 * every economic rule be tested without a database.
 */
@Entity
@Table(name = "own_fleet_cost_profile")
public class OwnFleetCostProfile {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "vehicle_type_id")
    private UUID vehicleTypeId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "fixed_trip_amount")
    private BigDecimal fixedTripAmount;

    @Column(name = "fuel_per_km")
    private BigDecimal fuelPerKm;

    @Column(name = "driver_per_hour")
    private BigDecimal driverPerHour;

    @Column(name = "vehicle_per_hour")
    private BigDecimal vehiclePerHour;

    @Column(name = "maintenance_per_km")
    private BigDecimal maintenancePerKm;

    @Column(name = "depreciation_per_km")
    private BigDecimal depreciationPerKm;

    @Column(name = "toll_amount")
    private BigDecimal tollAmount;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected OwnFleetCostProfile() {
    }

    public OwnFleetCostProfile(UUID companyId, UUID vehicleId, UUID vehicleTypeId, String currency,
            LocalDate effectiveFrom) {
        if (companyId == null) {
            throw new IllegalArgumentException("a cost profile belongs to a company");
        }
        if ((vehicleId == null) == (vehicleTypeId == null)) {
            throw new IllegalArgumentException(
                    "a cost profile is about one vehicle or about one vehicle type, not both and not neither");
        }
        this.companyId = companyId;
        this.vehicleId = vehicleId;
        this.vehicleTypeId = vehicleTypeId;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
    }

    /** The rates as a value, which is all the calculator is allowed to see. */
    public OwnFleetRates rates() {
        return new OwnFleetRates(currency, fixedTripAmount, fuelPerKm, driverPerHour, vehiclePerHour,
                maintenancePerKm, depreciationPerKm, tollAmount);
    }

    /**
     * Whether this profile governs the given day.
     *
     * <p>{@code effective_to} is exclusive, matching the half-open {@code daterange} the database
     * excludes overlaps over: a profile ending on the 1st and one starting on the 1st are a rate
     * change, not a conflict.
     */
    public boolean coversDate(LocalDate date) {
        if (date == null || date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || date.isBefore(effectiveTo);
    }

    /** Vehicle-specific profiles outrank vehicle-type ones. Nothing outranks a vehicle-specific one. */
    public boolean isVehicleSpecific() {
        return vehicleId != null;
    }

    /**
     * Whether this profile can produce a comparable total for a trip whose distance and duty are
     * both known - which is what {@code COMPLETE} means on the screen.
     *
     * <p>Deliberately not "has every component": a profile charging only a flat trip amount is
     * complete, because nothing it charges for can be missing. Completeness is about whether the
     * profile's own demands can be met, not about how many boxes were filled in.
     */
    public boolean isUsable() {
        return active && rates().chargesForAnything();
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public UUID vehicleTypeId() {
        return vehicleTypeId;
    }

    public String currency() {
        return currency;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    public boolean isActive() {
        return active;
    }

    public String notes() {
        return notes;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public void setRates(BigDecimal fixedTripAmount, BigDecimal fuelPerKm, BigDecimal driverPerHour,
            BigDecimal vehiclePerHour, BigDecimal maintenancePerKm, BigDecimal depreciationPerKm,
            BigDecimal tollAmount) {
        this.fixedTripAmount = fixedTripAmount;
        this.fuelPerKm = fuelPerKm;
        this.driverPerHour = driverPerHour;
        this.vehiclePerHour = vehiclePerHour;
        this.maintenancePerKm = maintenancePerKm;
        this.depreciationPerKm = depreciationPerKm;
        this.tollAmount = tollAmount;
    }

    public void setWindow(LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setCreatedBy(UUID actorId) {
        this.createdBy = actorId;
    }

    public void setUpdatedBy(UUID actorId) {
        this.updatedBy = actorId;
    }
}
