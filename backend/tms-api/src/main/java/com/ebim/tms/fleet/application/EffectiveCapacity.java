package com.ebim.tms.fleet.application;

import java.math.BigDecimal;

/**
 * The capacity actually in effect for one vehicle, after resolving each dimension independently
 * (vehicle override first, otherwise the vehicle type's default). See
 * {@link EffectiveCapacityResolver}.
 *
 * <p>Since V62 (ADR-022 section 5) also the load space: length, width and height come from the
 * vehicle type only (a vehicle has no dimension override), and the floor fill factor from the type,
 * else the company, else the deployment. Null dimensions mean "no such limit".
 */
public record EffectiveCapacity(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, int maxPallets, BigDecimal lengthM,
        BigDecimal widthM, BigDecimal heightM, BigDecimal floorFillFactor) {

    /** The pre-V62 shape: weight, volume and pallets only. */
    public EffectiveCapacity(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, int maxPallets) {
        this(maxWeightKg, maxVolumeM3, maxPallets, null, null, null, null);
    }
}
