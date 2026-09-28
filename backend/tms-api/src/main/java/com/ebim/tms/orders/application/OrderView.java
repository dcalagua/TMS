package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TotalsSource;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
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
        /**
         * What happened to the goods, which {@code status} deliberately does not say. An order is
         * {@code PLANNED} while it is delivered, refused or brought back; see
         * {@link OrderFulfillmentStatus} for why the two are separate columns rather than one
         * enum with more values.
         */
        OrderFulfillmentStatus fulfillmentStatus,
        String cancelReason,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal totalPallets,
        BigDecimal declaredWeightKg,
        BigDecimal declaredVolumeM3,
        BigDecimal declaredPallets,
        TotalsSource totalsSource,
        int lineCount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        // Additive (docs/domain/SPLIT_ORDER_EXECUTION.md, L2): how much of the order is committed to
        // trips and how much is still to place. A READY_FOR_PLANNING order with allocated pallets is
        // partly planned - possibly partly on the road already.
        BigDecimal allocatedWeightKg,
        BigDecimal allocatedVolumeM3,
        BigDecimal allocatedPallets,
        BigDecimal pendingWeightKg,
        BigDecimal pendingVolumeM3,
        BigDecimal pendingPallets) {

    public static OrderView from(TransportOrder order, MasterReference origin, MasterReference destination,
            long lineCount, OrderFulfillmentStatus fulfillmentStatus) {
        return new OrderView(order.id(), order.orderNumber(), order.externalSource(), order.externalReference(),
                order.originId(), origin == null ? null : origin.code(), origin == null ? null : origin.name(),
                order.destinationId(), destination == null ? null : destination.code(),
                destination == null ? null : destination.name(), order.customerName(), order.customerReference(),
                order.serviceDate(), order.priority(), order.requestedWindowStart(), order.requestedWindowEnd(),
                order.status(), fulfillmentStatus, order.cancelReason(), order.totalWeightKg(), order.totalVolumeM3(),
                order.totalPallets(), order.declaredWeightKg(), order.declaredVolumeM3(), order.declaredPallets(),
                order.totalsSource(),
                (int) lineCount, order.version(), order.createdAt(), order.updatedAt(),
                order.allocated().weightKg(), order.allocated().volumeM3(), order.allocated().pallets(),
                order.allocation().pending().weightKg(), order.allocation().pending().volumeM3(),
                order.allocation().pending().pallets());
    }
}
