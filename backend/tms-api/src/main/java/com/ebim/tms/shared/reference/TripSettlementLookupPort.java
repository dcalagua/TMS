package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Who ran a shipment, and what it is called (migration V46).
 *
 * <p>Answered by {@code planning}, which owns {@code tms.trip}. Kept apart from
 * {@link TripCostLookupPort} because they are different facts owned by different modules: what a
 * shipment cost belongs to {@code rates}, and who ran it belongs to {@code planning}. One port
 * spanning both would make one module answer for the other's data.
 *
 * <p>The carrier is the control that matters here. An invoice from carrier A that bills a shipment
 * carrier B ran is either a mistake or a fraud, and it is the first thing a freight auditor checks.
 */
public interface TripSettlementLookupPort {

    /** What each of these shipments is, keyed by trip id. Absent means it is not this company's. */
    Map<UUID, TripSettlementSummary> findForSettlement(Collection<UUID> tripIds, UUID companyId);

    /**
     * @param carrierId the owner of the assigned vehicle. Null when the shipment has no carrier -
     *                  own fleet, or one not yet assigned - which cannot be billed by anybody
     */
    record TripSettlementSummary(UUID tripId, String shipmentNumber, UUID carrierId) {
    }
}
