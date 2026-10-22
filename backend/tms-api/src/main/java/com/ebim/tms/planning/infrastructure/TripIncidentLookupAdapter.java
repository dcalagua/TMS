package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.shared.reference.TripIncidentLookupPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A trip's incidents, as the freight claims of settlement need them (V71), answered by the module
 * that owns the trip, its deliveries and its exceptions.
 *
 * <p>Read-only: a claim is a document about something that already happened, and letting it change
 * the record of what happened would be the wrong way round - the same rule
 * {@link TripSettlementLookupAdapter} follows for invoices.
 */
@Component
class TripIncidentLookupAdapter implements TripIncidentLookupPort {

    private static final String TRIPS_SQL = """
            SELECT id, shipment_number, carrier_id, status, planning_date
              FROM tms.trip
             WHERE company_id = :companyId AND id IN (:ids)
            """;

    private static final String DELIVERY_SQL = """
            SELECT id, trip_id, order_id, result, delivered_at, notes, refused_weight_kg, refused_pallets
              FROM tms.order_delivery
             WHERE id = :id AND company_id = :companyId
            """;

    private static final String DELIVERY_LINES_SQL = """
            SELECT order_line_id, uom, quantity_attempted, quantity_delivered, quantity_refused, notes
              FROM tms.order_delivery_line
             WHERE order_delivery_id = :id AND company_id = :companyId
             ORDER BY created_at, id
            """;

    private static final String EXCEPTION_COLUMNS = """
            SELECT e.id, e.trip_id, t.shipment_number, t.carrier_id, e.trip_stop_id, e.exception_type, e.status,
                   e.reported_at, e.notes, e.resolved_at
              FROM tms.trip_exception e
              JOIN tms.trip t ON t.id = e.trip_id AND t.company_id = e.company_id
             WHERE e.company_id = :companyId
            """;

    private final JdbcClient jdbcClient;

    TripIncidentLookupAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClaimableTrip> findTrip(UUID tripId, UUID companyId) {
        return Optional.ofNullable(findTrips(List.of(tripId), companyId).get(tripId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ClaimableTrip> findTrips(Collection<UUID> tripIds, UUID companyId) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ClaimableTrip> trips = new HashMap<>();
        jdbcClient.sql(TRIPS_SQL)
                .param("companyId", companyId)
                .param("ids", tripIds)
                .query((rs, row) -> new ClaimableTrip(rs.getObject("id", UUID.class), rs.getString("shipment_number"),
                        rs.getObject("carrier_id", UUID.class), rs.getString("status"),
                        "DRAFT".equals(rs.getString("status")), rs.getObject("planning_date", LocalDate.class)))
                .list()
                .forEach(trip -> trips.put(trip.tripId(), trip));
        return Map.copyOf(trips);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeliveryIncident> findDelivery(UUID orderDeliveryId, UUID companyId) {
        List<DeliveryIncidentLine> lines = jdbcClient.sql(DELIVERY_LINES_SQL)
                .param("id", orderDeliveryId)
                .param("companyId", companyId)
                .query((rs, row) -> new DeliveryIncidentLine(rs.getObject("order_line_id", UUID.class),
                        rs.getString("uom"), rs.getBigDecimal("quantity_attempted"),
                        rs.getBigDecimal("quantity_delivered"), rs.getBigDecimal("quantity_refused"),
                        rs.getString("notes")))
                .list();
        return jdbcClient.sql(DELIVERY_SQL)
                .param("id", orderDeliveryId)
                .param("companyId", companyId)
                .query((rs, row) -> new DeliveryIncident(rs.getObject("id", UUID.class),
                        rs.getObject("trip_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("result"), rs.getObject("delivered_at", OffsetDateTime.class),
                        rs.getString("notes"), rs.getBigDecimal("refused_weight_kg"),
                        rs.getBigDecimal("refused_pallets"), lines))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExceptionIncident> findException(UUID exceptionId, UUID companyId) {
        return jdbcClient.sql(EXCEPTION_COLUMNS + " AND e.id = :id")
                .param("companyId", companyId)
                .param("id", exceptionId)
                .query((rs, row) -> exceptionOf(rs))
                .optional();
    }

    /**
     * Rides {@code ix_trip_exception_company_open} (V27) for the open ones, which is the incident
     * centre's default and the only one a supervisor opens every morning.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ExceptionIncident> findExceptions(UUID companyId, Boolean open, UUID carrierId, String type,
            OffsetDateTime from, OffsetDateTime to, int limit) {
        StringBuilder sql = new StringBuilder(EXCEPTION_COLUMNS);
        Map<String, Object> params = new HashMap<>();
        params.put("companyId", companyId);
        if (open != null) {
            sql.append(" AND e.status = :status");
            params.put("status", open ? "OPEN" : "RESOLVED");
        }
        if (carrierId != null) {
            sql.append(" AND t.carrier_id = :carrierId");
            params.put("carrierId", carrierId);
        }
        if (type != null) {
            sql.append(" AND e.exception_type = :type");
            params.put("type", type);
        }
        if (from != null) {
            sql.append(" AND e.reported_at >= :from");
            params.put("from", from);
        }
        if (to != null) {
            sql.append(" AND e.reported_at < :to");
            params.put("to", to);
        }
        sql.append(" ORDER BY e.reported_at DESC, e.id LIMIT :limit");
        params.put("limit", limit);
        return jdbcClient.sql(sql.toString())
                .params(params)
                .query((rs, row) -> exceptionOf(rs))
                .list();
    }

    private static ExceptionIncident exceptionOf(ResultSet rs) throws SQLException {
        return new ExceptionIncident(rs.getObject("id", UUID.class), rs.getObject("trip_id", UUID.class),
                rs.getString("shipment_number"), rs.getObject("carrier_id", UUID.class),
                rs.getObject("trip_stop_id", UUID.class), rs.getString("exception_type"), rs.getString("status"),
                rs.getObject("reported_at", OffsetDateTime.class), rs.getString("notes"),
                rs.getObject("resolved_at", OffsetDateTime.class));
    }
}
