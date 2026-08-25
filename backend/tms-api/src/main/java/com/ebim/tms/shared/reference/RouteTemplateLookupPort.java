package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.List;
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

    /**
     * Every active corridor leaving one origin, ordered by code. An automatic planning engine
     * uses these to group orders that are normally served together - which is the difference
     * between a proposal a dispatcher recognises and a set of mathematically valid truckloads
     * nobody would drive.
     */
    List<RouteTemplate> findActiveByOriginInCompany(UUID originId, UUID companyId);

    /**
     * The corridor's reference distance in kilometres, or empty when the route has none (and when
     * the id names no route of this company).
     *
     * <p>Deliberately a method of its own rather than a field on {@link RouteTemplate}, which
     * states plainly that it carries no reference distance because "copying it onto a shipment
     * would be publishing a figure nobody measured". That stance is unchanged. This exists for the
     * one caller that needs the number <em>as an input it will label</em>: {@code rates} multiplies
     * it by a per-km tariff and records on the cost line where it came from
     * ({@code quantity_source = 'ROUTE_REFERENCE'}, migration V30), so nothing here ever presents a
     * planner's estimate as a measured road distance.
     *
     * <p>Active or not, for the same reason {@link #findAllInCompany} is: a shipment planned from a
     * corridor that has since been retired still has the distance it was priced against.
     */
    Optional<BigDecimal> findReferenceDistanceKm(UUID routeId, UUID companyId);
}
