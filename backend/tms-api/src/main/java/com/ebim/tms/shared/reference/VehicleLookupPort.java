package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way {@code planning} resolves a vehicle and its effective capacity without depending on
 * {@code com.ebim.tms.fleet} (rule 10 in {@code docs/database/DATA_MODEL.md} section 13).
 *
 * <p>Implemented by {@code fleet.application.VehicleLookupService} rather than by an adapter in
 * {@code fleet.infrastructure}: resolving effective capacity is a fleet <em>rule</em>
 * ({@code EffectiveCapacityResolver}), not a repository translation, and rules live in the
 * application layer - the same split {@link OrderPlanningPort} documents.
 */
public interface VehicleLookupPort {

    /**
     * Resolves a vehicle that may be planned right now: same company, {@code active}, and
     * currently {@code AVAILABLE} (not in maintenance or out of service). Empty for anything
     * else, so planning answers 400/404 without ever learning why a vehicle of another company
     * does not exist.
     */
    Optional<VehicleCapacityReference> findAssignable(UUID vehicleId, UUID companyId);

    /**
     * Resolves every id in {@code ids} that belongs to {@code companyId}, active or not, in one
     * batched call - for read-only display of vehicles already referenced by a trip (a vehicle
     * deactivated after a plan was confirmed must still render), never for validating a new
     * assignment.
     */
    Map<UUID, VehicleCapacityReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
