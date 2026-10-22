package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The emission factor of a vehicle type (migration V73), for {@code settlement} without depending on
 * {@code fleet}. Implemented in {@code fleet.infrastructure}.
 */
public interface EmissionFactorLookupPort {

    /**
     * @param kgCo2ePerKm null when the type has no factor: its trips have no footprint, never a zero one
     */
    record EmissionFactor(UUID vehicleTypeId, String vehicleTypeCode, String vehicleTypeName, FuelType fuelType,
            BigDecimal kgCo2ePerKm) {
    }

    /** Every id in {@code vehicleTypeIds} of {@code companyId}, active or not, in one call. */
    Map<UUID, EmissionFactor> factorsOf(Set<UUID> vehicleTypeIds, UUID companyId);
}
