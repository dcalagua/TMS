package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * What a trip carried, for allocating its cost to its orders (V68, ADR-026): the trip's facts and
     * its ACTIVE assignment rows - the ledger, never the order header, so a split order (V37) is
     * costed on each trip by the part that trip carried. Empty when the trip is not this company's.
     */
    Optional<TripAllocationSource> findAllocationSource(UUID tripId, UUID companyId);

    /**
     * @param cancelled   a cancelled trip is never allocated
     * @param departedAt  {@code actual_departure_at}, null if it never left
     * @param planningDate the day it was planned for; the service date when it never left
     */
    record TripAllocationSource(
            UUID tripId,
            String shipmentNumber,
            UUID carrierId,
            UUID routeId,
            boolean cancelled,
            OffsetDateTime departedAt,
            LocalDate planningDate,
            List<TripLoad> loads) {

        public TripAllocationSource {
            loads = List.copyOf(loads);
        }
    }

    /** One ACTIVE row of {@code tms.trip_order_assignment}; zero is "not known" (V11). */
    record TripLoad(
            UUID assignmentId,
            UUID orderId,
            BigDecimal weightKg,
            BigDecimal volumeM3,
            BigDecimal pallets,
            BigDecimal floorAreaM2) {
    }
}
