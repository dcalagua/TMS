package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.shared.reference.EmissionFactorLookupPort;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link EmissionFactorLookupPort} (migration V73): a plain repository
 * translation, like {@link VehicleTypeLookupAdapter}. Inactive types resolve too - a trip that ran
 * on a type retired since still drove what it drove.
 */
@Component
class EmissionFactorLookupAdapter implements EmissionFactorLookupPort {

    private final VehicleTypeRepository vehicleTypeRepository;

    EmissionFactorLookupAdapter(VehicleTypeRepository vehicleTypeRepository) {
        this.vehicleTypeRepository = vehicleTypeRepository;
    }

    @Override
    public Map<UUID, EmissionFactor> factorsOf(Set<UUID> vehicleTypeIds, UUID companyId) {
        Map<UUID, EmissionFactor> byId = new HashMap<>();
        if (vehicleTypeIds.isEmpty()) {
            return byId;
        }
        for (VehicleType type : vehicleTypeRepository.findByIdInAndCompanyId(vehicleTypeIds, companyId)) {
            byId.put(type.id(), new EmissionFactor(type.id(), type.code(), type.name(), type.fuelType(),
                    type.emissionFactorKgCo2ePerKm()));
        }
        return byId;
    }
}
