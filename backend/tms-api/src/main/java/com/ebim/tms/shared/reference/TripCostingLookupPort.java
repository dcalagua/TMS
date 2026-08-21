package com.ebim.tms.shared.reference;

import java.util.Optional;
import java.util.UUID;

/**
 * The one way {@code com.ebim.tms.rates} reads a trip, without depending on
 * {@code com.ebim.tms.planning} (migration V30). The counterpart of
 * {@link TripCostEstimationPort}, which points the other way.
 *
 * <p>{@code planning.infrastructure.TripCostingLookupAdapter} is the only implementation, and it
 * is deliberately the only place a {@link CostableTrip} is built: the on-demand estimate and the
 * one that runs at confirmation then price the same shipment from the same numbers, instead of two
 * call sites each assembling their own idea of what the trip weighs.
 */
public interface TripCostingLookupPort {

    /**
     * The trip as something that can be priced, or empty when the id names no trip of this
     * company - so a costing endpoint answers 404 without ever learning whether another tenant's
     * trip exists.
     *
     * <p>Resolves a cancelled or completed trip too. Both still have costs: a completed one is the
     * ordinary case for recording an actual, and a cancelled one may still have been invoiced.
     * What a caller may <em>do</em> with each is {@code TripCostService}'s rule, not this port's.
     */
    Optional<CostableTrip> findCostableTrip(UUID tripId, UUID companyId);
}
