package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The destination counterpart of {@link OriginLookupAdapter}; see that class's comment. */
@Component
class DestinationLookupAdapter implements DestinationLookupPort {

    private final DestinationRepository destinationRepository;

    DestinationLookupAdapter(DestinationRepository destinationRepository) {
        this.destinationRepository = destinationRepository;
    }

    @Override
    public Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId) {
        return destinationRepository.findByIdAndCompanyId(id, companyId)
                .filter(Destination::active)
                .map(DestinationLookupAdapter::toReference);
    }

    @Override
    public Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, MasterReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Destination destination : destinationRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(destination.id(), toReference(destination));
        }
        return byId;
    }

    private static MasterReference toReference(Destination destination) {
        return new MasterReference(destination.id(), destination.code(), destination.name());
    }
}
