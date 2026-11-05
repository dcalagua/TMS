package com.ebim.tms.planning.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes of {@code tms.order_delivery_line} (V45): what arrived per order line, in the
 * line's own unit. Written since V75 by {@code TripDeliveryService}, the one place a delivery result
 * is recorded, through {@link #upsert}.
 *
 * <p>Planning's own table only. The line's number and material live on the orders module's
 * {@code transport_order_line} and are resolved through {@code OrderReferencePort#linesByIds}, one
 * batched call for the whole set, as {@link DocumentDeliveryQueries} does for its names.
 */
@Repository
public class OrderDeliveryLineQueries {

    private final JdbcClient jdbc;

    public OrderDeliveryLineQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One delivery line as stored; {@code uom} is the unit snapshotted when it was recorded. */
    public record DeliveryLineRow(UUID orderDeliveryId, UUID orderLineId, String uom, BigDecimal quantityAttempted,
            BigDecimal quantityDelivered, BigDecimal quantityRefused, String notes) {
    }

    /** The lines of each delivery in {@code orderDeliveryIds}, one query. A delivery with none is absent. */
    public Map<UUID, List<DeliveryLineRow>> linesOf(UUID companyId, Collection<UUID> orderDeliveryIds) {
        Map<UUID, List<DeliveryLineRow>> byDelivery = new HashMap<>();
        if (orderDeliveryIds.isEmpty()) {
            return byDelivery;
        }
        jdbc.sql("""
                SELECT order_delivery_id, order_line_id, uom, quantity_attempted, quantity_delivered, quantity_refused,
                       notes
                FROM tms.order_delivery_line
                WHERE company_id = :companyId AND order_delivery_id IN (:ids)
                """).param("companyId", companyId).param("ids", orderDeliveryIds)
                .query((ResultSet rs, int row) -> {
                    DeliveryLineRow line = new DeliveryLineRow(rs.getObject("order_delivery_id", UUID.class),
                            rs.getObject("order_line_id", UUID.class), rs.getString("uom"),
                            rs.getBigDecimal("quantity_attempted"), rs.getBigDecimal("quantity_delivered"),
                            rs.getBigDecimal("quantity_refused"), rs.getString("notes"));
                    byDelivery.computeIfAbsent(line.orderDeliveryId(), key -> new ArrayList<>()).add(line);
                    return line;
                }).list();
        return byDelivery;
    }

    /** Whether any delivery of the order recorded a line result - see {@code OrderFulfillmentPort#hasLineResults}. */
    public boolean anyForOrder(UUID companyId, UUID orderId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tms.order_delivery_line
                               WHERE company_id = :companyId AND order_id = :orderId)
                """).param("companyId", companyId).param("orderId", orderId)
                .query(Boolean.class).single());
    }

    /**
     * Writes the line results of one delivery (V75), inserting new ones and overwriting the rest.
     * Never deletes: the table grants no DELETE (V45) because what was signed for is corrected, not
     * erased, so a correction sends every line of the order - with zeros where nothing happened.
     */
    public void upsert(UUID companyId, UUID orderDeliveryId, UUID orderId, List<DeliveryLineWrite> lines,
            UUID actorId) {
        for (DeliveryLineWrite line : lines) {
            jdbc.sql("""
                    INSERT INTO tms.order_delivery_line (company_id, order_delivery_id, order_id, order_line_id,
                        uom, quantity_attempted, quantity_delivered, quantity_refused, notes, created_by, updated_by)
                    VALUES (:companyId, :deliveryId, :orderId, :lineId, :uom, :attempted, :delivered, :refused,
                        :notes, :actorId, :actorId)
                    ON CONFLICT (order_delivery_id, order_line_id) DO UPDATE SET
                        uom = EXCLUDED.uom,
                        quantity_attempted = EXCLUDED.quantity_attempted,
                        quantity_delivered = EXCLUDED.quantity_delivered,
                        quantity_refused = EXCLUDED.quantity_refused,
                        notes = EXCLUDED.notes,
                        updated_at = now(),
                        updated_by = EXCLUDED.updated_by
                    """)
                    .param("companyId", companyId).param("deliveryId", orderDeliveryId).param("orderId", orderId)
                    .param("lineId", line.orderLineId()).param("uom", line.uom())
                    .param("attempted", line.quantityAttempted()).param("delivered", line.quantityDelivered())
                    .param("refused", line.quantityRefused()).param("notes", line.notes())
                    .param("actorId", actorId)
                    .update();
        }
    }

    /** One line result to write; {@code uom} is the line's unit at the moment of the delivery. */
    public record DeliveryLineWrite(UUID orderLineId, String uom, BigDecimal quantityAttempted,
            BigDecimal quantityDelivered, BigDecimal quantityRefused, String notes) {
    }
}
