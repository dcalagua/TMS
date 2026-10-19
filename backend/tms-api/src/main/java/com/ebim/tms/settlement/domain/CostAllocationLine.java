package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one assignment row received from one run (V68, ADR-026): {@code tms.trip_cost_allocation}.
 * {@code allocatedAmount} null means the row could not be measured and received nothing.
 */
public record CostAllocationLine(
        UUID orderId,
        UUID assignmentId,
        String orderNumber,
        String customerLabel,
        UUID destinationId,
        String destinationLabel,
        BigDecimal weightKg,
        BigDecimal volumeM3,
        BigDecimal pallets,
        BigDecimal floorAreaM2,
        BigDecimal basisQuantity,
        BigDecimal share,
        BigDecimal allocatedAmount,
        String reason) {
}
