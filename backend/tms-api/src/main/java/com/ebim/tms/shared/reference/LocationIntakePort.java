package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;

/**
 * The one way the integration module writes a location without depending on
 * {@code com.ebim.tms.masterdata} - the "explicit API" pattern
 * {@link OriginLookupPort} established and {@link OrderPlanningPort} extended to writes.
 *
 * <p>Implemented by {@code masterdata.application.LocationIntakeService}, which delegates to
 * {@code LocationService}. That indirection is the point: an inbound integration must not be a
 * second, more permissive door into the same table. Code normalisation, the coordinate pair
 * rule, the time zone check, the external-identity uniqueness guard and the origin/destination
 * projection all happen exactly once, in the module that owns them, so an integration cannot
 * create a location the Locations screen would have refused.
 */
public interface LocationIntakePort {

    /**
     * Creates or updates one location from a sending system's payload, inside the caller's
     * transaction so a batch stays atomic per item.
     *
     * @param scope the tenant, resolved from the integration credential and never from the
     *     payload - see {@code CompanyScope}
     */
    LocationIntakeResult upsert(CompanyScope scope, LocationIntakeCommand command);
}
