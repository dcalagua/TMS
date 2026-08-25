package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The destination counterpart of {@link OriginLookupPort} - see that interface's class comment
 * for the module-boundary reasoning. Since V23 a destination is a canonical {@code Location}
 * holding the {@code DESTINATION} role, so "active in company" here means company, active state
 * and role. {@code masterdata.infrastructure.DestinationLookupAdapter} is the only
 * implementation.
 */
public interface DestinationLookupPort {

    /** See {@link OriginLookupPort#findActiveInCompany(UUID, UUID)}. */
    Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId);

    /** See {@link OriginLookupPort#findAllInCompany(Set, UUID)}. */
    Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId);

    /** See {@link OriginLookupPort#findActiveByCodesInCompany(Collection, UUID)}. */
    Map<String, MasterReference> findActiveByCodesInCompany(Collection<String> codes, UUID companyId);
}
