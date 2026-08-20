package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves a carrier's code and name for display, without {@code planning} depending on
 * {@code com.ebim.tms.fleet} - the carrier sibling of {@link VehicleLookupPort}.
 *
 * <p>Exists because {@code tms.trip.carrier_id} is stored explicitly rather than derived from the
 * vehicle (migration V11: "a later change to the vehicle master cannot silently rewrite who was
 * planned to run this trip"), and a read model that resolved the carrier's <em>name</em> from the
 * vehicle would give that guarantee away again - a vehicle moved to another carrier would make a
 * confirmed shipment display a carrier it was never planned with. So the id and the name come
 * from the same place: the carrier the trip points at.
 *
 * <p>No {@code findActive*} method: nothing validates a new carrier reference through this port.
 * A trip's carrier is always resolved from the vehicle being attached ({@code TripService}), so
 * the only remaining question is a display one, and display must resolve a deactivated carrier
 * too - see {@link MasterReference}.
 */
public interface CarrierLookupPort {

    /** See {@link OriginLookupPort#findAllInCompany(Set, UUID)} - batched, active or not. */
    Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
