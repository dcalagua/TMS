package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.VehicleBodyType;
import com.ebim.tms.fleet.domain.VehicleType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link VehicleType}, kept separate from the JPA entity (review chain rule). */
public record VehicleTypeView(
        UUID id,
        String code,
        String name,
        BigDecimal maxWeightKg,
        BigDecimal maxVolumeM3,
        int maxPallets,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        VehicleBodyType bodyType,
        boolean temperatureControlled,
        BigDecimal minTemperatureCelsius,
        BigDecimal maxTemperatureCelsius,
        Integer axles,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static VehicleTypeView from(VehicleType type) {
        return new VehicleTypeView(type.id(), type.code(), type.name(), type.maxWeightKg(), type.maxVolumeM3(),
                type.maxPallets(), type.lengthM(), type.widthM(), type.heightM(), type.bodyType(),
                type.temperatureControlled(), type.minTemperatureCelsius(), type.maxTemperatureCelsius(),
                type.axles(), type.active(), type.createdAt(), type.updatedAt());
    }
}
