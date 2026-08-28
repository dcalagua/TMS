package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.PlannableOrder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One row of the eligible-order list: the order header a planner needs to decide where it goes,
 * with its destination resolved for display. Never carries lines - a planning board summarises
 * thousands of orders and must not load what it does not render (the step brief's performance
 * rule).
 */
public record EligibleOrderView(
        UUID id,
        String orderNumber,
        UUID originId,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        String customerName,
        String customerReference,
        LocalDate serviceDate,
        String priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal totalPallets,
        // What is still to be planned (migration V37). Equal to the totals for an untouched order,
        // and smaller once part of it is already on a truck - which is the figure a planner needs
        // in front of them when deciding what to put on the next one.
        BigDecimal pendingWeightKg,
        BigDecimal pendingVolumeM3,
        BigDecimal pendingPallets,
        // Whether some of this order is already elsewhere. Carried as its own flag rather than
        // left to the reader to infer from two numbers being different, because "this is a split"
        // is the thing a board has to say out loud.
        boolean partiallyAllocated) {

    public static EligibleOrderView from(PlannableOrder order, MasterReference destination) {
        return new EligibleOrderView(order.id(), order.orderNumber(), order.originId(), order.destinationId(),
                destination == null ? null : destination.code(), destination == null ? null : destination.name(),
                order.customerName(), order.customerReference(), order.serviceDate(), order.priority(),
                order.requestedWindowStart(), order.requestedWindowEnd(), order.totalWeightKg(),
                order.totalVolumeM3(), order.totalPallets(), order.pending().weightKg(),
                order.pending().volumeM3(), order.pending().pallets(), order.isPartiallyAllocated());
    }
}
