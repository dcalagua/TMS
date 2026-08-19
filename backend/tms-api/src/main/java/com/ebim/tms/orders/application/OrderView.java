package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.shared.reference.MasterReference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The list-row view of a {@link TransportOrder}: origin/destination names and a line
 * <em>count</em>, never the lines themselves - the same N+1-avoiding split
 * {@link com.ebim.tms.masterdata.application.RouteView} uses against
 * {@link com.ebim.tms.masterdata.application.RouteDetailView}. See {@link OrderDetailView} for
 * the shape every other endpoint returns.
 */
public record OrderView(
        UUID id,
        String orderNumber,
        String externalSource,
        String externalReference,
        UUID originId,
        String originCode,
        String originName,
        UUID destinationId,
        String destinationCode,
        String destinationName,
        String customerName,
        String customerReference,
        LocalDate serviceDate,
        OrderPriority priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        OrderStatus status,
        String cancelReason,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal totalPallets,
        int lineCount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static OrderView from(TransportOrder order, MasterReference origin, MasterReference destination, long lineCount) {
        return new OrderView(order.id(), order.orderNumber(), order.externalSource(), order.externalReference(),
                order.originId(), origin == null ? null : origin.code(), origin == null ? null : origin.name(),
                order.destinationId(), destination == null ? null : destination.code(),
                destination == null ? null : destination.name(), order.customerName(), order.customerReference(),
                order.serviceDate(), order.priority(), order.requestedWindowStart(), order.requestedWindowEnd(),
                order.status(), order.cancelReason(), order.totalWeightKg(), order.totalVolumeM3(), order.totalPallets(),
                (int) lineCount, order.version(), order.createdAt(), order.updatedAt());
    }
}
