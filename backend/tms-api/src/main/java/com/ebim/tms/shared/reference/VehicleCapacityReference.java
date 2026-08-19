package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The planning-facing view of a vehicle: its identity, its carrier and the effective capacity
 * {@code fleet}'s {@code EffectiveCapacityResolver} has already resolved (vehicle override first,
 * otherwise the vehicle type's default). Planning never re-derives capacity from a vehicle type -
 * there is exactly one resolver and it lives in {@code fleet}
 * ({@code docs/architecture/OWNERSHIP_MATRIX.md}, "Capacity checks").
 *
 * <p>Carries no {@code fleet} type, so {@code planning} can use it without depending on
 * {@code fleet} ({@code ModuleBoundaryTest}). See {@link VehicleLookupPort}.
 *
 * @param maxPallets may be zero for a vehicle that carries none (a tanker); zero is a real limit,
 *                   not "unlimited" - see {@code docs/domain/CAPACITY_MODEL.md}
 */
public record VehicleCapacityReference(
        UUID id,
        String code,
        String licensePlate,
        UUID carrierId,
        String carrierName,
        String vehicleTypeCode,
        BigDecimal maxWeightKg,
        BigDecimal maxVolumeM3,
        Integer maxPallets,
        boolean active,
        String availabilityStatus) {
}
