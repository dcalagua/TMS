package com.ebim.tms.shared.reference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves a vehicle type by id without the caller depending on {@code com.ebim.tms.fleet} - the
 * vehicle-type sibling of {@link CarrierLookupPort}, added with migration V30 for
 * {@code com.ebim.tms.rates}, whose cards may be narrowed to one type.
 *
 * <p>Deliberately narrow: it answers "does this id name a vehicle type of this company, and what
 * is it called". It does <em>not</em> expose the type's default capacity, because there is exactly
 * one place that turns a vehicle type into capacity ({@code fleet}'s
 * {@code EffectiveCapacityResolver}, reached through {@link VehicleLookupPort}) and a second door
 * onto the same question is how two answers start to disagree.
 *
 * <p>{@code fleet.infrastructure.VehicleTypeLookupAdapter} is the only implementation.
 */
public interface VehicleTypeLookupPort {

    /**
     * Resolves a vehicle type a caller may point a <em>new</em> reference at: same company and
     * {@code active}. Empty for anything else, so a caller answers 400 without ever learning
     * whether a vehicle type of another company exists.
     */
    Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId);

    /**
     * Resolves every id in {@code ids} that belongs to {@code companyId}, active or not, in one
     * batched call - for read-only display of a type an already-persisted row points at. Same
     * active/display split, and the same reason for it, as
     * {@link OriginLookupPort#findAllInCompany}.
     */
    Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId);
}
