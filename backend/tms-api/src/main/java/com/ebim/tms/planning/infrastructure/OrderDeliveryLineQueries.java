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
 * Reads of {@code tms.order_delivery_line} (V45): what arrived per order line, in the line's own
 * unit. Read-only here; the table has no Java writer yet.
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
            BigDecimal quantityDelivered, BigDecimal quantityRefused) {
    }

    /** The lines of each delivery in {@code orderDeliveryIds}, one query. A delivery with none is absent. */
    public Map<UUID, List<DeliveryLineRow>> linesOf(UUID companyId, Collection<UUID> orderDeliveryIds) {
        Map<UUID, List<DeliveryLineRow>> byDelivery = new HashMap<>();
        if (orderDeliveryIds.isEmpty()) {
            return byDelivery;
        }
        jdbc.sql("""
                SELECT order_delivery_id, order_line_id, uom, quantity_attempted, quantity_delivered, quantity_refused
                FROM tms.order_delivery_line
                WHERE company_id = :companyId AND order_delivery_id IN (:ids)
                """).param("companyId", companyId).param("ids", orderDeliveryIds)
                .query((ResultSet rs, int row) -> {
                    DeliveryLineRow line = new DeliveryLineRow(rs.getObject("order_delivery_id", UUID.class),
                            rs.getObject("order_line_id", UUID.class), rs.getString("uom"),
                            rs.getBigDecimal("quantity_attempted"), rs.getBigDecimal("quantity_delivered"),
                            rs.getBigDecimal("quantity_refused"));
                    byDelivery.computeIfAbsent(line.orderDeliveryId(), key -> new ArrayList<>()).add(line);
                    return line;
                }).list();
        return byDelivery;
    }
}
