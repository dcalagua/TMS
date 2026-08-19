package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One active order-to-trip assignment as the API reports it.
 *
 * <p>{@code assignedWeightKg}/{@code assignedVolumeM3}/{@code assignedPallets} are the amounts
 * from the assignment row, not from the order header: today they are identical (V1 assigns whole
 * orders), and the day a partial assignment exists this view keeps telling the truth about what
 * is actually on the truck.
 */
public record TripAssignmentView(
        UUID assignmentId,
        UUID orderId,
        String orderNumber,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        String customerName,
        LocalDate serviceDate,
        String priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        BigDecimal assignedWeightKg,
        BigDecimal assignedVolumeM3,
        BigDecimal assignedPallets,
        boolean wholeOrder,
        OffsetDateTime assignedAt) {
}
