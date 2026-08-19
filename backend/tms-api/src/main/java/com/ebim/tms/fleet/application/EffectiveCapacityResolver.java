package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleType;
import org.springframework.stereotype.Service;

/**
 * The single place that resolves a vehicle's effective capacity: each of weight/volume/pallets
 * is taken from the vehicle's own override when present, otherwise from its vehicle type's
 * default. The three dimensions are resolved independently - a vehicle may override only one of
 * them and still fall back to the type's defaults for the other two (step brief: "optional
 * capacity overrides for weight/volume/pallets").
 *
 * <p>Stateless by design (no repository access): every caller (fleet CRUD today, planning/
 * capacity checks later - see {@code docs/architecture/OWNERSHIP_MATRIX.md}, "Capacity checks")
 * already has both the {@link Vehicle} and its {@link VehicleType} in hand, so this stays a pure
 * function rather than performing its own lookups.
 */
@Service
public class EffectiveCapacityResolver {

    public EffectiveCapacity resolve(Vehicle vehicle, VehicleType type) {
        var maxWeightKg = vehicle.maxWeightOverrideKg() != null ? vehicle.maxWeightOverrideKg() : type.maxWeightKg();
        var maxVolumeM3 = vehicle.maxVolumeOverrideM3() != null ? vehicle.maxVolumeOverrideM3() : type.maxVolumeM3();
        int maxPallets = vehicle.maxPalletsOverride() != null ? vehicle.maxPalletsOverride() : type.maxPallets();
        return new EffectiveCapacity(maxWeightKg, maxVolumeM3, maxPallets);
    }
}
