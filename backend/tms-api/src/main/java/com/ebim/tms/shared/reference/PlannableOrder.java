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
 * @param totalWeightKg  kilograms, never tons - the unit is in the name, like every capacity
 *                       column in the schema
 * @param totalVolumeM3  cubic meters, never cm3
 * @param totalPallets   may be fractional, matching {@code transport_order.total_pallets}
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
        BigDecimal totalPallets) {
}
