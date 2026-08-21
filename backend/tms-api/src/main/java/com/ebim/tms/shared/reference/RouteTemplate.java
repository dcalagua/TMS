package com.ebim.tms.shared.reference;

import java.util.List;
import java.util.UUID;

/**
 * A master route as a <em>template</em>, for a module that plans shipments and must not depend on
 * {@code com.ebim.tms.masterdata} (see {@link OriginLookupPort}'s class comment for the boundary
 * rule this shape exists to satisfy).
 *
 * <p>The name is the contract. A route is not a plan and this record is not a plan either: it is
 * "the order in which this corridor is normally served", offered to a planner as a suggestion.
 * Nothing in {@code planning} builds a stop out of it - {@code tms.trip_stop} always follows the
 * trip's own active assignments - and nothing keeps a shipment equal to it after the master is
 * edited. See {@code docs/domain/SHIPMENT_V2.md}, "Route master interaction".
 *
 * <p>Carries no reference distance/duration: those are planner-entered hints on the master
 * ({@code V8}) and copying them onto a shipment would be publishing a figure nobody measured.
 *
 * @param originId       the corridor's origin - a route may only be applied to a shipment whose
 *                       planning run departs from the same origin
 * @param destinationIds every stop of the master route, in the master's own sequence (1..N,
 *                       contiguous - {@code RouteService.replaceStops} guarantees it)
 */
public record RouteTemplate(
        UUID id, String code, String name, UUID originId, List<UUID> destinationIds, boolean active) {

    public RouteTemplate {
        destinationIds = List.copyOf(destinationIds);
    }
}
