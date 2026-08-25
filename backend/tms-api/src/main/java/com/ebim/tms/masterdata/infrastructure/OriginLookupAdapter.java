package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link OriginLookupPort}. Since V23 an "origin" is not a record of
 * its own: it is a {@code tms.location} holding the {@link LocationRole#ORIGIN} role, and this
 * class is the three-line statement of that fact. All the work is in
 * {@link LocationReferenceAdapter}; see {@link OriginLookupPort} for why the indirection exists
 * at all.
 */
@Component
class OriginLookupAdapter implements OriginLookupPort {

    private final LocationReferenceAdapter locations;

    OriginLookupAdapter(LocationReferenceAdapter locations) {
        this.locations = locations;
    }

    @Override
    public Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId) {
        return locations.usableAs(id, companyId, LocationRole.ORIGIN);
    }

    @Override
    public Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        return locations.referencedBy(ids, companyId);
    }

    @Override
    public Map<String, MasterReference> findActiveByCodesInCompany(Collection<String> codes, UUID companyId) {
        return locations.usableAsByCodes(codes, companyId, LocationRole.ORIGIN);
    }
}
