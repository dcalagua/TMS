package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What TMS priced a shipment at, and what it actually cost (migration V46).
 *
 * <p>The port {@code settlement} reads and {@code rates} answers. Settlement <b>never writes</b>
 * these figures: two owners of "what this shipment cost" is exactly how two numbers come to
 * disagree, and V30's close/reopen already governs when that figure may change.
 *
 * <p>Batched, so an invoice with twenty lines resolves its shipments in one query rather than
 * twenty - the same discipline {@code VehicleLookupPort} and {@code StopServicePort} follow.
 */
public interface TripCostLookupPort {

    /**
     * What is known about each of these shipments, keyed by trip id.
     *
     * <p>A shipment absent from the result has no cost row at all. A shipment present with a null
     * {@code expectedAmount} has a cost row that nobody estimated - and those are different facts:
     * the first means TMS has never costed it, the second that costing was attempted and produced
     * nothing. Neither is zero.
     */
    Map<UUID, TripCostSummary> findCosts(Collection<UUID> tripIds, UUID companyId);

    /**
     * @param expectedAmount what the rate card produced, or <b>null when nobody estimated it</b>.
     *                       Never zero for unknown - reading it that way would report an entire
     *                       invoice as an overcharge
     * @param actualAmount   what was recorded as spent, or null when nobody recorded it
     * @param currency       what the shipment was priced in, or null when it has no cost row
     */
    record TripCostSummary(
            UUID tripId,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            String currency) {
    }
}
