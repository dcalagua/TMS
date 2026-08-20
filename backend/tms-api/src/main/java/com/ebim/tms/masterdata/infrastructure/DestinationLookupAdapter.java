package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    @Override
    public Map<String, MasterReference> findActiveByCodesInCompany(Collection<String> codes, UUID companyId) {
        Map<String, MasterReference> byCode = new HashMap<>();
        // Stored codes are upper-cased and trimmed (ck_destination_code_normalized), so normalising the
        // caller's input the same way turns "case-insensitive lookup" into an exact IN match the
        // company/code index can serve, instead of a lower() scan over the whole company.
        Set<String> normalized = codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) {
            return byCode;
        }
        for (Destination row : destinationRepository.findByCompanyIdAndCodeIn(companyId, normalized)) {
            if (row.active()) {
                byCode.put(row.code(), toReference(row));
            }
        }
        return byCode;
    }

    private static MasterReference toReference(Destination destination) {
        return new MasterReference(destination.id(), destination.code(), destination.name(),
                destination.latitude(), destination.longitude());
    }
}
