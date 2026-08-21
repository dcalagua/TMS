package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way {@code planning} resolves a driver without depending on {@code com.ebim.tms.fleet}
 * (rule 10 in {@code docs/database/DATA_MODEL.md} section 13) - the driver sibling of
 * {@link VehicleLookupPort}.
 *
 * <p>Implemented in {@code fleet.infrastructure} rather than in {@code fleet.application}: both
 * methods are plain repository translations, unlike {@code VehicleLookupService}, which resolves
 * effective capacity and is therefore a fleet <em>rule</em>. Same split
 * {@link CarrierLookupPort}'s adapter documents.
 *
 * <p><b>The licence rule is deliberately not behind this port.</b> {@link #findAssignable} filters
 * on {@code active} only and hands back {@code licenseExpiresOn} for the caller to judge, because
 * "is this licence still valid" has no answer without a date, and the date that matters is the
 * trip's - its planning date when a planner assigns, the current day when a dispatcher sends the
 * truck out. A port that answered it would have to pick one of those and be wrong about the other.
 */
public interface DriverLookupPort {

    /**
     * Resolves a driver who may be assigned right now: same company and {@code active}. Empty for
     * anything else, so planning answers 400/404 without ever learning whether a driver of another
     * company exists - the same discipline every cross-module lookup in TMS follows.
     */
    Optional<DriverReference> findAssignable(UUID driverId, UUID companyId);

    /**
     * Resolves every id in {@code ids} that belongs to {@code companyId}, active or not, in one
     * batched call - for read-only display of drivers already named by a trip. A driver
     * deactivated after leaving the fleet must still render on the shipments they ran, which is
     * why this one does not filter on {@code active} and must never be used to validate a new
     * assignment.
     */
    Map<UUID, DriverReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
