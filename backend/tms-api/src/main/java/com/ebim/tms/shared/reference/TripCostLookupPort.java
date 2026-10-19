package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
     * @param closed         whether the cost was closed (V30): its actual is final, and is what an
     *                       invoice is compared against (V64, ADR-024)
     * @param estimatedComponents the estimate's breakdown by component name ({@code RateComponent}
     *                       names, as strings so settlement does not depend on rates). Empty when
     *                       there is no estimate; a NOT_CALCULABLE component is present at 0.00,
     *                       which is what the estimate counted for it
     * @param closedAt       when the cost was last closed, null while open. Each close is a distinct
     *                       instant (a reopen clears it), which is what keys an accrual adjustment
     *                       (V65, ADR-023)
     */
    record TripCostSummary(
            UUID tripId,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            String currency,
            boolean closed,
            Map<String, BigDecimal> estimatedComponents,
            OffsetDateTime closedAt) {

        public TripCostSummary {
            estimatedComponents = estimatedComponents == null ? Map.of() : Map.copyOf(estimatedComponents);
        }

        /** The V64 shape, without the closing instant. */
        public TripCostSummary(UUID tripId, BigDecimal expectedAmount, BigDecimal actualAmount, String currency,
                boolean closed, Map<String, BigDecimal> estimatedComponents) {
            this(tripId, expectedAmount, actualAmount, currency, closed, estimatedComponents, null);
        }

        /** An open cost with no breakdown. */
        public TripCostSummary(UUID tripId, BigDecimal expectedAmount, BigDecimal actualAmount, String currency) {
            this(tripId, expectedAmount, actualAmount, currency, false, Map.of());
        }
    }
}
