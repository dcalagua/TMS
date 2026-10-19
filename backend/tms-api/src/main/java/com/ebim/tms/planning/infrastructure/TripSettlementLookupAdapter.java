package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripAllocationSource;
import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripLoad;
import com.ebim.tms.shared.reference.TripSettlementLookupPort;
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
 * Who ran a shipment (V46), answered by the module that owns {@code tms.trip}.
 *
 * <p>Read-only. Settlement never moves a shipment - an invoice is a document about work already
 * done, and letting the audit of it change the record of it would be the wrong way round.
 */
@Component
class TripSettlementLookupAdapter implements TripSettlementLookupPort {

    private static final String TRIP_SQL = """
            SELECT id, shipment_number, carrier_id, route_id, status, actual_departure_at, planning_date
              FROM tms.trip
             WHERE id = :tripId AND company_id = :companyId
            """;

    private static final String LOADS_SQL = """
            SELECT id, order_id, assigned_weight_kg, assigned_volume_m3, assigned_pallets, assigned_floor_area_m2
              FROM tms.trip_order_assignment
             WHERE trip_id = :tripId AND company_id = :companyId AND status = 'ACTIVE'
             ORDER BY assigned_at, id
            """;

    private final TripRepository tripRepository;
    private final JdbcClient jdbcClient;

    TripSettlementLookupAdapter(TripRepository tripRepository, JdbcClient jdbcClient) {
        this.tripRepository = tripRepository;
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TripSettlementSummary> findForSettlement(Collection<UUID> tripIds, UUID companyId) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TripSettlementSummary> byTrip = new HashMap<>();
        for (Trip trip : tripRepository.findByIdInAndCompanyId(tripIds, companyId)) {
            byTrip.put(trip.id(), new TripSettlementSummary(trip.id(), trip.shipmentNumber(), trip.carrierId()));
        }
        return Map.copyOf(byTrip);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TripAllocationSource> findAllocationSource(UUID tripId, UUID companyId) {
        List<TripLoad> loads = jdbcClient.sql(LOADS_SQL)
                .param("tripId", tripId)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new TripLoad(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getBigDecimal("assigned_weight_kg"),
                        rs.getBigDecimal("assigned_volume_m3"),
                        rs.getBigDecimal("assigned_pallets"),
                        rs.getBigDecimal("assigned_floor_area_m2")))
                .list();
        return jdbcClient.sql(TRIP_SQL)
                .param("tripId", tripId)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new TripAllocationSource(
                        rs.getObject("id", UUID.class),
                        rs.getString("shipment_number"),
                        rs.getObject("carrier_id", UUID.class),
                        rs.getObject("route_id", UUID.class),
                        "CANCELLED".equals(rs.getString("status")),
                        rs.getObject("actual_departure_at", OffsetDateTime.class),
                        rs.getObject("planning_date", LocalDate.class),
                        loads))
                .optional();
    }
}
