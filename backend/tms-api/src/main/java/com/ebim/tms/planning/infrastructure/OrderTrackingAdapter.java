package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.shared.reference.OrderTrackingPort;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link OrderTrackingPort} (ADR-029): one query over planning's own tables -
 * trip, assignment, stop, delivery - for one order.
 *
 * <p>A trip is a leg when it is neither {@code DRAFT} nor {@code CANCELLED} and either still carries the
 * order ({@code ACTIVE} assignment) or recorded a result for it. The stop is the one the delivery was
 * recorded at, or, before a delivery, the trip's stop for the order's destination - the rule by which
 * {@code TripStopPlanner} builds stops from assignments.
 *
 * <p>Capped at ten legs: an order that has been on more trips than that is a planning problem, and the
 * customer page needs the latest of them, not a history.
 */
@Component
class OrderTrackingAdapter implements OrderTrackingPort {

    private static final String LEGS_SQL = """
            SELECT t.status AS trip_status, a.status AS assignment_status, t.planning_date,
                   t.planned_departure_at, t.actual_departure_at, t.actual_completion_at,
                   t.carrier_id, t.vehicle_id,
                   s.execution_status, s.actual_arrival_at, s.eta_arrival_at,
                   s.service_window_start, s.service_window_end,
                   d.result, d.delivered_at, d.receiver_name
            FROM tms.trip t
            JOIN LATERAL (
                SELECT oa.status, oa.assigned_at
                FROM tms.trip_order_assignment oa
                WHERE oa.trip_id = t.id AND oa.company_id = t.company_id AND oa.order_id = :orderId
                ORDER BY (oa.status = 'ACTIVE') DESC, oa.assigned_at DESC
                LIMIT 1
            ) a ON true
            LEFT JOIN tms.order_delivery d
                   ON d.trip_id = t.id AND d.company_id = t.company_id AND d.order_id = :orderId
            LEFT JOIN tms.trip_stop s
                   ON s.trip_id = t.id AND s.company_id = t.company_id
                  AND (s.id = d.trip_stop_id OR (d.id IS NULL AND s.destination_id = :destinationId))
            WHERE t.company_id = :companyId
              AND t.status NOT IN ('DRAFT', 'CANCELLED')
              AND (a.status = 'ACTIVE' OR d.id IS NOT NULL)
            ORDER BY t.planning_date, coalesce(d.delivered_at, t.actual_departure_at, t.planned_departure_at),
                     a.assigned_at
            LIMIT 10
            """;

    private final JdbcClient jdbc;

    OrderTrackingAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OrderTrackingLeg> legsOf(UUID orderId, UUID destinationId, UUID companyId) {
        return jdbc.sql(LEGS_SQL)
                .param("orderId", orderId)
                .param("destinationId", destinationId)
                .param("companyId", companyId)
                .query((rs, n) -> new OrderTrackingLeg(
                        rs.getString("trip_status"),
                        "ACTIVE".equals(rs.getString("assignment_status")),
                        rs.getObject("planning_date", LocalDate.class),
                        rs.getObject("planned_departure_at", OffsetDateTime.class),
                        rs.getObject("actual_departure_at", OffsetDateTime.class),
                        rs.getObject("actual_completion_at", OffsetDateTime.class),
                        rs.getObject("carrier_id", UUID.class),
                        rs.getObject("vehicle_id", UUID.class),
                        rs.getString("execution_status"),
                        rs.getObject("actual_arrival_at", OffsetDateTime.class),
                        rs.getObject("eta_arrival_at", OffsetDateTime.class),
                        rs.getObject("service_window_start", LocalTime.class),
                        rs.getObject("service_window_end", LocalTime.class),
                        rs.getString("result"),
                        rs.getObject("delivered_at", OffsetDateTime.class),
                        rs.getString("receiver_name")))
                .list();
    }
}
