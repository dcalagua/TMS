package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import com.ebim.tms.fleet.domain.VehicleType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-facing view of a {@link Vehicle}, kept separate from the JPA entity (review chain rule).
 *
 * <p>Carries both the raw overrides and the already-resolved {@code effective*} capacity
 * (via {@link EffectiveCapacityResolver}), so the frontend list/detail never has to re-implement
 * the override-first resolution rule itself.
 */
public record VehicleView(
        UUID id,
        String code,
        String licensePlate,
        UUID carrierId,
        String carrierCode,
        String carrierBusinessName,
        UUID vehicleTypeId,
        String vehicleTypeCode,
        String vehicleTypeName,
        BigDecimal maxWeightOverrideKg,
        BigDecimal maxVolumeOverrideM3,
        Integer maxPalletsOverride,
        BigDecimal effectiveMaxWeightKg,
        BigDecimal effectiveMaxVolumeM3,
        int effectiveMaxPallets,
        VehicleAvailabilityStatus availabilityStatus,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param carrier the vehicle's carrier, already resolved by the caller (batched for lists) -
     *     {@code null} when the vehicle has no carrier.
     * @param type the vehicle's type, already resolved by the caller - never {@code null}
     *     ({@code vehicleTypeId} is mandatory), used both for display and to compute the
     *     effective capacity.
     */
    public static VehicleView from(Vehicle vehicle, Carrier carrier, VehicleType type, EffectiveCapacity capacity) {
        return new VehicleView(vehicle.id(), vehicle.code(), vehicle.licensePlate(), vehicle.carrierId(),
                carrier == null ? null : carrier.code(), carrier == null ? null : carrier.businessName(),
                vehicle.vehicleTypeId(), type == null ? null : type.code(), type == null ? null : type.name(),
                vehicle.maxWeightOverrideKg(), vehicle.maxVolumeOverrideM3(), vehicle.maxPalletsOverride(),
                capacity.maxWeightKg(), capacity.maxVolumeM3(), capacity.maxPallets(), vehicle.availabilityStatus(),
                vehicle.active(), vehicle.createdAt(), vehicle.updatedAt());
    }
}
