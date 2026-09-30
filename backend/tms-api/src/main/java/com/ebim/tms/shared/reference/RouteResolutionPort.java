package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Which master route carries an order, asked by a module that must not depend on
 * {@code com.ebim.tms.masterdata} (ADR-014 section 6). The rule itself is
 * {@link RouteResolution#resolve}; the implementation only loads the routes it is applied to.
 *
 * <p>Batched for a board: a page of two hundred orders resolves its distinct origin/destination
 * pairs in one query, never one per row.
 */
public interface RouteResolutionPort {

    /** The origin/destination pair an order travels, which is all route resolution looks at. */
    record OriginDestination(UUID originId, UUID destinationId) {
    }

    RouteResolution resolve(UUID companyId, UUID originId, UUID destinationId);

    /** Every pair in one call. A pair absent from the answer never happens; each gets a resolution. */
    Map<OriginDestination, RouteResolution> resolveAll(UUID companyId, Collection<OriginDestination> pairs);
}
