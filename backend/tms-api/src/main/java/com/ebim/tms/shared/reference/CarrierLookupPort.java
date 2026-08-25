package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
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
 * <p>{@link #findActiveInCompany} arrived later, with migration V30: a trip's carrier is still
 * always resolved from the vehicle being attached ({@code TripService}) and never validated here,
 * but a rate card names a carrier <em>directly</em> and must refuse a deactivated one. The two
 * methods are the same active/display split every other lookup port draws, and for the same
 * reason - display must resolve a deactivated carrier, a new reference must not create one
 * (see {@link MasterReference}).
 */
public interface CarrierLookupPort {

    /**
     * Resolves a carrier a caller may point a <em>new</em> reference at: same company and
     * {@code active}. Empty for anything else, so a caller answers 400 without ever learning
     * whether a carrier of another company exists.
     */
    Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId);

    /** See {@link OriginLookupPort#findAllInCompany(Set, UUID)} - batched, active or not. */
    Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
