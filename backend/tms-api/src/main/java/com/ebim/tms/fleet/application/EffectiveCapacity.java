package com.ebim.tms.fleet.application;

import java.math.BigDecimal;

/**
 * The capacity actually in effect for one vehicle, after resolving each dimension independently
 * (vehicle override first, otherwise the vehicle type's default). See
 * {@link EffectiveCapacityResolver}.
 */
public record EffectiveCapacity(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, int maxPallets) {
}
