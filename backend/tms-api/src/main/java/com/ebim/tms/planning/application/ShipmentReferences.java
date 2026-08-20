package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a set of trips needs resolved from elsewhere to be rendered as shipment headers,
 * loaded once for the whole set.
 *
 * <p>Exists to make the batching rule impossible to forget rather than merely documented: the
 * assembler builds one of these per call and then formats rows out of maps, so there is no code
 * path on which rendering row N+1 can issue a query. A 300-trip board costs a fixed five lookups
 * regardless of its size ({@code docs/domain/PLANNING_MANUAL_V1.md} section 10).
 *
 * <p>A missing entry is normal, never an error: a trip may have no vehicle, no carrier and no
 * route, and a master that was deleted out from under a reference cannot happen (every foreign
 * key is {@code ON DELETE RESTRICT}). Callers therefore read with {@code get} and render null.
 */
record ShipmentReferences(
        Map<UUID, PlanningRun> runs,
        Map<UUID, MasterReference> origins,
        Map<UUID, VehicleCapacityReference> vehicles,
        Map<UUID, MasterReference> carriers,
        Map<UUID, RouteTemplate> routes) {

    static final ShipmentReferences EMPTY =
            new ShipmentReferences(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
}
