package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves master routes, with their zone, for a module that shows them and must not depend on
 * {@code com.ebim.tms.masterdata}. The display counterpart of {@link RouteTemplateLookupPort},
 * which carries what planning needs to apply a route and no zone.
 * {@code masterdata.infrastructure.RouteReferenceLookupAdapter} is the only implementation.
 */
public interface RouteReferenceLookupPort {

    /**
     * Every id in {@code ids} that belongs to {@code companyId}, active or not, in one batched call.
     * Ids of another company are absent, never resolved.
     */
    Map<UUID, RouteReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
