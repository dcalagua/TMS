package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way a business module other than {@code masterdata} may resolve an origin, without
 * depending on {@code com.ebim.tms.masterdata} directly.
 *
 * <p>{@code masterdata} owns {@code Origin}; {@code ModuleBoundaryTest} forbids any other
 * business module (starting with {@code orders}, step 09) from importing it. This port is the
 * "explicit API" {@code ModuleBoundaryTest.business_modules_must_not_depend_on_each_other}'s
 * message points to: it lives in {@code shared} (which no business module is forbidden from
 * depending on) and carries no {@code masterdata}-specific type, so {@code shared} itself stays
 * free of any business-module dependency. {@code masterdata.infrastructure.OriginLookupAdapter}
 * is the only class that implements it.
 */
public interface OriginLookupPort {

    /**
     * Resolves an origin for a <em>new</em> assignment (create/update), refusing one that is
     * inactive or belongs to another company - the step 09 brief's "prevent assigning
     * inactive/mismatched-company masters".
     */
    Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId);

    /**
     * Resolves every id in {@code ids} that belongs to {@code companyId}, active or not, in one
     * batched call - for read-only display of already-persisted references (a list page, a
     * detail view), never for validating a new assignment. One call per page, not one per row:
     * the same N+1 discipline {@code RouteService.loadByIds} established.
     */
    Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
