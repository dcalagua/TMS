package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.shared.reference.AccruableTripLookupPort;
import com.ebim.tms.shared.settings.AccrualTrigger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code planning}'s answer to {@link AccruableTripLookupPort} (V65, ADR-023).
 *
 * <p>Native SQL over {@code tms.trip} alone - the table this module owns. Two column choices, one
 * statement each, so the planner can use the partial indexes V65 adds instead of an OR across them.
 */
@Repository
class AccruableTripLookupAdapter implements AccruableTripLookupPort {

    private static final String COLUMNS = """
            t.id, t.shipment_number, t.carrier_id, t.vehicle_id, t.route_id,
            t.actual_departure_at, t.actual_completion_at
            """;

    private static final String COMPANIES_SQL = """
            SELECT DISTINCT company_id FROM tms.trip
             WHERE actual_departure_at >= :since AND status <> 'CANCELLED'
            UNION
            SELECT DISTINCT company_id FROM tms.trip
             WHERE actual_completion_at >= :since AND status <> 'CANCELLED'
            """;

    private final JdbcClient jdbcClient;

    AccruableTripLookupAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<UUID> companiesWithExecutionSince(OffsetDateTime since) {
        return new HashSet<>(jdbcClient.sql(COMPANIES_SQL)
                .param("since", since)
                .query((rs, rowNum) -> rs.getObject("company_id", UUID.class))
                .list());
    }

    @Override
    public List<AccruableTrip> findExecuted(UUID companyId, AccrualTrigger trigger, OffsetDateTime from,
            OffsetDateTime to, AccruableTrip after, int limit) {
        // The column name is one of two literals chosen here, never caller text.
        String fact = trigger == AccrualTrigger.DELIVERED ? "t.actual_completion_at" : "t.actual_departure_at";
        String keyset = after == null ? "" : " AND (" + fact + ", t.id) > (:afterAt, :afterId)";
        JdbcClient.StatementSpec statement = jdbcClient.sql("SELECT " + COLUMNS + " FROM tms.trip t"
                        + " WHERE t.company_id = :companyId AND t.status <> 'CANCELLED'"
                        + " AND " + fact + " >= :from AND " + fact + " < :to" + keyset
                        + " ORDER BY " + fact + ", t.id LIMIT :limit")
                .param("companyId", companyId)
                .param("from", from)
                .param("to", to)
                .param("limit", limit);
        if (after != null) {
            statement = statement.param("afterAt", after.factAt(trigger)).param("afterId", after.tripId());
        }
        return statement.query((rs, rowNum) -> map(rs)).list();
    }

    @Override
    public Map<UUID, AccruableTrip> findByIds(Collection<UUID> tripIds, UUID companyId) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AccruableTrip> byId = new HashMap<>();
        jdbcClient.sql("SELECT " + COLUMNS + " FROM tms.trip t WHERE t.company_id = :companyId AND t.id IN (:ids)")
                .param("companyId", companyId)
                .param("ids", List.copyOf(tripIds))
                .query((rs, rowNum) -> map(rs))
                .list()
                .forEach(trip -> byId.put(trip.tripId(), trip));
        return Map.copyOf(byId);
    }

    private static AccruableTrip map(ResultSet rs) throws SQLException {
        return new AccruableTrip(
                rs.getObject("id", UUID.class),
                rs.getString("shipment_number"),
                rs.getObject("carrier_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getObject("actual_departure_at", OffsetDateTime.class),
                rs.getObject("actual_completion_at", OffsetDateTime.class));
    }
}
