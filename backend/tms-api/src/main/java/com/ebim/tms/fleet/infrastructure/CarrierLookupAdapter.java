package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link CarrierLookupPort}: a plain repository translation, so it
 * lives in {@code infrastructure} rather than in {@code application} where
 * {@code VehicleLookupService} sits - that one resolves effective capacity, which is a fleet
 * rule; this one resolves a name.
 *
 * <p>{@code businessName} is the carrier's name: a carrier is a company, and
 * {@code MasterReference.name} is "the label a human reads for this id". A carrier also has no
 * coordinates of its own, so the reference reports none - see {@link MasterReference#of}.
 */
@Component
class CarrierLookupAdapter implements CarrierLookupPort {

    private final CarrierRepository carrierRepository;

    CarrierLookupAdapter(CarrierRepository carrierRepository) {
        this.carrierRepository = carrierRepository;
    }

    @Override
    public Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, MasterReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Carrier carrier : carrierRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(carrier.id(), MasterReference.of(carrier.id(), carrier.code(), carrier.businessName()));
        }
        return byId;
    }
}
