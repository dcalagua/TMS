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
 * @param vehicleTypeId  the type's id beside its code, so a consumer that must <em>match</em> on
 *                       the type rather than display it does not have to compare user-facing
 *                       strings. A rate card narrowed to one vehicle type (migration V30) is the
 *                       first such consumer; a code is renameable and an id is not.
 * @param maxPallets may be zero for a vehicle that carries none (a tanker); zero is a real limit,
 *                   not "unlimited" - see {@code docs/domain/CAPACITY_MODEL.md}
 */
public record VehicleCapacityReference(
        UUID id,
        String code,
        String licensePlate,
        UUID carrierId,
        String carrierName,
        UUID vehicleTypeId,
        String vehicleTypeCode,
        BigDecimal maxWeightKg,
        BigDecimal maxVolumeM3,
        Integer maxPallets,
        boolean active,
        String availabilityStatus,
        /** The vehicle type's load space (V62); null = no such limit. */
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        /** Already resolved by fleet: type, else company, else deployment. */
        BigDecimal floorFillFactor) {

    /** The pre-V62 shape: no load-space dimensions, so floor and height are unlimited. */
    public VehicleCapacityReference(UUID id, String code, String licensePlate, UUID carrierId, String carrierName,
            UUID vehicleTypeId, String vehicleTypeCode, BigDecimal maxWeightKg, BigDecimal maxVolumeM3,
            Integer maxPallets, boolean active, String availabilityStatus) {
        this(id, code, licensePlate, carrierId, carrierName, vehicleTypeId, vehicleTypeCode, maxWeightKg, maxVolumeM3,
                maxPallets, active, availabilityStatus, null, null, null, null);
    }
}
