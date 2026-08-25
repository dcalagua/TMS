package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The destination counterpart of {@link OriginLookupAdapter}: a "destination" is a
 * {@code tms.location} holding the {@link LocationRole#DESTINATION} role. See
 * {@link LocationReferenceAdapter} for the shared body.
 */
@Component
class DestinationLookupAdapter implements DestinationLookupPort {

    private final LocationReferenceAdapter locations;

    DestinationLookupAdapter(LocationReferenceAdapter locations) {
        this.locations = locations;
    }

    @Override
    public Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId) {
        return locations.usableAs(id, companyId, LocationRole.DESTINATION);
    }

    @Override
    public Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        return locations.referencedBy(ids, companyId);
    }

    @Override
    public Map<String, MasterReference> findActiveByCodesInCompany(Collection<String> codes, UUID companyId) {
        return locations.usableAsByCodes(codes, companyId, LocationRole.DESTINATION);
    }
}
