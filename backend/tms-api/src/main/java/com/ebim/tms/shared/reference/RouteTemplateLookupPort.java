package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way {@code planning} resolves a master route without depending on
 * {@code com.ebim.tms.masterdata} - the route counterpart of {@link OriginLookupPort}, and the
 * same active/display split for the same reason.
 * {@code masterdata.infrastructure.RouteTemplateLookupAdapter} is the only implementation.
 */
public interface RouteTemplateLookupPort {

    /**
     * Resolves a route a planner may apply <em>right now</em>: same company and {@code active}.
     * Empty for anything else, so planning answers 400 without ever learning whether a route of
     * another company exists.
     */
    Optional<RouteTemplate> findActiveInCompany(UUID routeId, UUID companyId);

    /**
     * Resolves every id in {@code ids} that belongs to {@code companyId}, active or not, in one
     * batched call - for read-only display of a route a shipment already points at. A route
     * deactivated after a shipment was planned from it must still render its code and name, the
     * same invariant {@code MasterReference}'s class comment documents.
     *
     * <p>Batched, because a planning board of 300 shipments must resolve their routes in one
     * query, not 300 ({@code docs/domain/PLANNING_MANUAL_V1.md} section 10).
     */
    Map<UUID, RouteTemplate> findAllInCompany(Set<UUID> ids, UUID companyId);
}
