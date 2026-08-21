package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TotalsSource;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.domain.TransportOrderLine;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The single-order view of a {@link TransportOrder}, including every line. Returned by
 * get/create/update/mark-ready/cancel - never by list, which uses {@link OrderView} instead (see
 * that record's class comment for why).
 */
public record OrderDetailView(
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
         * What happened to the goods - see {@link OrderFulfillmentStatus}, and note that it is
         * beside {@code status} rather than inside it. Derived from the delivery rows on read,
         * so there is no second copy of the fact to drift.
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
        List<OrderLineView> lines,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * There is deliberately no overload that defaults {@code fulfillmentStatus}. Every caller has
     * to say what happened to the goods, because the one caller that could honestly assume
     * "nothing yet" is order creation, and a default would let the others assume it by accident -
     * which is how a cancelled-after-delivery order would come back reporting {@code PENDING}.
     */
    public static OrderDetailView from(TransportOrder order, MasterReference origin, MasterReference destination,
            OrderFulfillmentStatus fulfillmentStatus) {
        List<OrderLineView> lines = order.lines().stream().map(OrderLineView::from).toList();
        return new OrderDetailView(order.id(), order.orderNumber(), order.externalSource(), order.externalReference(),
                order.originId(), origin == null ? null : origin.code(), origin == null ? null : origin.name(),
                order.destinationId(), destination == null ? null : destination.code(),
                destination == null ? null : destination.name(), order.customerName(), order.customerReference(),
                order.serviceDate(), order.priority(), order.requestedWindowStart(), order.requestedWindowEnd(),
                order.status(), fulfillmentStatus, order.cancelReason(), order.totalWeightKg(), order.totalVolumeM3(),
                order.totalPallets(), order.declaredWeightKg(), order.declaredVolumeM3(), order.declaredPallets(),
                order.totalsSource(),
                lines, order.version(), order.createdAt(), order.updatedAt());
    }

    public record OrderLineView(
            UUID id,
            int lineNumber,
            String materialCode,
            String materialDescription,
            BigDecimal quantity,
            String uom,
            BigDecimal unitWeightKg,
            BigDecimal unitVolumeM3,
            BigDecimal lineWeightKg,
            BigDecimal lineVolumeM3,
            BigDecimal palletQuantity) {

        static OrderLineView from(TransportOrderLine line) {
            return new OrderLineView(line.id(), line.lineNumber(), line.materialCode(), line.materialDescription(),
                    line.quantity(), line.uom(), line.unitWeightKg(), line.unitVolumeM3(), line.lineWeightKg(),
                    line.lineVolumeM3(), line.palletQuantity());
        }
    }
}
