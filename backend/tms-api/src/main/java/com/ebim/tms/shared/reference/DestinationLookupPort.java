package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The destination counterpart of {@link OriginLookupPort} - see that interface's class comment
 * for the module-boundary reasoning. {@code masterdata.infrastructure.DestinationLookupAdapter}
 * is the only implementation.
 */
public interface DestinationLookupPort {

    /** See {@link OriginLookupPort#findActiveInCompany(UUID, UUID)}. */
    Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId);

    /** See {@link OriginLookupPort#findAllInCompany(Set, UUID)}. */
    Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
