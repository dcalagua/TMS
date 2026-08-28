package com.ebim.tms.costing.domain;

import java.math.BigDecimal;

/**
 * The seven rates a profile may carry, without the persistence around them (V48, JOB 22).
 *
 * <p>Split from the entity so {@link OwnFleetCostCalculator} stays a pure function over a value: a
 * calculator that took the JPA entity would drag a persistence context into every unit test and
 * make the arithmetic provable only against a database.
 *
 * <p><b>Null means the profile does not charge for that component. Zero means it charges nothing
 * for it.</b> Those are different statements and the calculator treats them differently - see
 * {@code V48__own_fleet_cost_profile.sql}, which states the same rule at the column.
 */
public record OwnFleetRates(
        String currency,
        BigDecimal fixedTripAmount,
        BigDecimal fuelPerKm,
        BigDecimal driverPerHour,
        BigDecimal vehiclePerHour,
        BigDecimal maintenancePerKm,
        BigDecimal depreciationPerKm,
        BigDecimal tollAmount) {

    public OwnFleetRates {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("an own-fleet cost needs an ISO-4217 currency");
        }
    }

    public BigDecimal rateFor(OwnFleetComponent component) {
        return switch (component) {
            case FIXED_TRIP -> fixedTripAmount;
            case FUEL_PER_KM -> fuelPerKm;
            case DRIVER_PER_HOUR -> driverPerHour;
            case VEHICLE_PER_HOUR -> vehiclePerHour;
            case MAINTENANCE_PER_KM -> maintenancePerKm;
            case DEPRECIATION_PER_KM -> depreciationPerKm;
            case TOLL -> tollAmount;
        };
    }

    /** Whether the profile charges for anything at all. The database refuses one that does not. */
    public boolean chargesForAnything() {
        for (OwnFleetComponent component : OwnFleetComponent.values()) {
            if (rateFor(component) != null) {
                return true;
            }
        }
        return false;
    }

    /** Whether any component needs a distance before this profile can produce a total. */
    public boolean needsDistance() {
        for (OwnFleetComponent component : OwnFleetComponent.values()) {
            if (component.needsDistance() && rateFor(component) != null) {
                return true;
            }
        }
        return false;
    }

    /** Whether any component needs a duty duration before this profile can produce a total. */
    public boolean needsDuty() {
        for (OwnFleetComponent component : OwnFleetComponent.values()) {
            if (component.needsDuty() && rateFor(component) != null) {
                return true;
            }
        }
        return false;
    }
}
