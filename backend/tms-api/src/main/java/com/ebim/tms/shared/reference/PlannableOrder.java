package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * The planning-facing view of a transport order: everything the planning module needs to decide
 * whether an order may go on a trip, how much of the vehicle it consumes and where it has to be
 * delivered - and nothing else. Lines are deliberately absent: a planning board summarises 10,000
 * orders a day and must never load their lines (see {@code docs/domain/PLANNING_MANUAL_V1.md},
 * "Performance").
 *
 * <p>Carries no {@code orders} type, so {@code planning} can depend on it without depending on
 * {@code orders} ({@code ModuleBoundaryTest}); {@code priority} is the plain code rather than
 * {@code OrderPriority} for the same reason. See {@link OrderPlanningPort}.
 *
 * @param totalWeightKg      kilograms, never tons - the unit is in the name, like every capacity
 *                           column in the schema
 * @param totalVolumeM3      cubic meters, never cm3
 * @param totalPallets       may be fractional, matching {@code transport_order.total_pallets}
 * @param externalSource     the sending system's own identity for this order (inbound integration
 *                           API), or null for an order created by hand - carried here rather than
 *                           resolved separately so a shipment publication (job 08's
 *                           {@code ShipmentPublicationPort}) can echo back the same reference an
 *                           ERP sent in, without a second lookup into {@code orders}
 * @param externalReference  the sending system's identifier, paired with {@code externalSource}
 */
public record PlannableOrder(
        UUID id,
        String orderNumber,
        UUID originId,
        UUID destinationId,
        String customerName,
        String customerReference,
        LocalDate serviceDate,
        String priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal totalPallets,
        String externalSource,
        String externalReference) {
}
