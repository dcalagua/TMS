package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.VehicleTypeLookupPort;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link VehicleTypeLookupPort}: a plain repository translation, so it
 * lives here rather than in {@code application} - the same split {@link CarrierLookupAdapter}
 * documents.
 *
 * <p>Resolves the type's code and name and nothing else. Capacity deliberately does not come
 * through this door: there is one resolver for that ({@code EffectiveCapacityResolver}, reached
 * through {@code VehicleLookupPort}) and a second answer to the same question is how two answers
 * start to disagree.
 */
@Component
class VehicleTypeLookupAdapter implements VehicleTypeLookupPort {

    private final VehicleTypeRepository vehicleTypeRepository;

    VehicleTypeLookupAdapter(VehicleTypeRepository vehicleTypeRepository) {
        this.vehicleTypeRepository = vehicleTypeRepository;
    }

    @Override
    public Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId) {
        return vehicleTypeRepository.findByIdAndCompanyId(id, companyId)
                .filter(VehicleType::active)
                .map(type -> MasterReference.of(type.id(), type.code(), type.name()));
    }

    @Override
    public Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, MasterReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (VehicleType type : vehicleTypeRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(type.id(), MasterReference.of(type.id(), type.code(), type.name()));
        }
        return byId;
    }
}
