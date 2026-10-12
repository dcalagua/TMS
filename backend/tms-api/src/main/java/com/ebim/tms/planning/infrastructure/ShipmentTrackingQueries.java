package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.ShipmentDocumentState;
import com.ebim.tms.planning.domain.TripStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The dispatched-shipment tracking report (ADR-019 section 2), in plain SQL over planning's own
 * tables: the trip, its current warehouse dispatch (ADR-013), its stops and its tracked documents
 * (ADR-018). Names that live in other modules are resolved by the service through the ports.
 *
 * <p>A row is a trip with a current, matched dispatch that is not draft or cancelled - the same
 * rule that makes a trip visible to its carrier, applied here to every carrier.
 */
@Repository
public class ShipmentTrackingQueries {

    public record Filter(UUID companyId, OffsetDateTime dispatchedFrom, OffsetDateTime dispatchedBefore,
            UUID carrierId, TripStatus status, ShipmentDocumentState documents, String searchPattern) {
    }

    public record Row(UUID tripId, String shipmentNumber, TripStatus tripStatus, LocalDate planningDate,
            UUID carrierId, UUID planningRunId, UUID vehicleId, UUID driverId, String dispatchReference,
            OffsetDateTime dispatchedAt, String dispatchVerification, String dispatchPlate, String dispatchDriverName,
            int stopsTotal, int stopsResolved, int documentCount, int pendingCount, int deliveredCount,
            int rejectedCount, int notDeliveredCount, int returnedCount, int inconsistentCount,
            OffsetDateTime lastReportAt) {
    }

    public record Stop(UUID stopId, UUID tripId, int sequence, UUID destinationId, String executionStatus) {
    }

    public record Totals(long shipments, long inTransit, long completed, long withPendingDocuments,
            long documentsTotal, long documentsPending, long documentsDelivered, long documentsRejected,
            long documentsNotDelivered, long awaitingReturn, long dispatchMismatches) {
    }

    /** The base: one row per listed trip, with what left and how its documents stand. */
    private static final String BASE = """
            SELECT t.id, t.shipment_number, t.status, t.planning_date, t.carrier_id, t.planning_run_id,
                   t.vehicle_id, t.driver_id,
                   x.dispatch_reference, x.actual_dispatch_at, x.verification_status,
                   x.vehicle_license_plate, x.driver_name,
                   (SELECT count(*) FROM tms.trip_stop s
                    WHERE s.trip_id = t.id AND s.company_id = t.company_id) AS stops_total,
                   (SELECT count(*) FROM tms.trip_stop s
                    WHERE s.trip_id = t.id AND s.company_id = t.company_id
                      AND s.execution_status IN ('COMPLETED', 'SKIPPED', 'FAILED')) AS stops_resolved,
                   coalesce(d.total, 0) AS document_count,
                   coalesce(d.pending, 0) AS pending_count,
                   coalesce(d.delivered, 0) AS delivered_count,
                   coalesce(d.rejected, 0) AS rejected_count,
                   coalesce(d.not_delivered, 0) AS not_delivered_count,
                   coalesce(d.returned, 0) AS returned_count,
                   coalesce(d.awaiting_return, 0) AS awaiting_return_count,
                   coalesce(d.inconsistent, 0) AS inconsistent_count,
                   d.last_report_at
            FROM tms.trip t
            JOIN LATERAL (
                SELECT xd.dispatch_reference, xd.actual_dispatch_at, xd.verification_status,
                       xd.vehicle_license_plate, xd.driver_name
                FROM tms.external_dispatch xd
                WHERE xd.trip_id = t.id AND xd.company_id = t.company_id AND xd.superseded_at IS NULL
                ORDER BY xd.received_at DESC
                LIMIT 1
            ) x ON true
            LEFT JOIN LATERAL (
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE dd.status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE dd.status = 'DELIVERED') AS delivered,
                       count(*) FILTER (WHERE dd.status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE dd.status = 'NOT_DELIVERED') AS not_delivered,
                       count(*) FILTER (WHERE dd.returned_at IS NOT NULL) AS returned,
                       count(*) FILTER (WHERE dd.status <> 'PENDING' AND dd.returned_at IS NULL) AS awaiting_return,
                       count(*) FILTER (WHERE (dd.status = 'DELIVERED' AND EXISTS (
                               SELECT 1 FROM tms.order_delivery od
                               JOIN tms.document_delivery_order ddo
                                 ON ddo.order_id = od.order_id AND ddo.document_delivery_id = dd.id
                               WHERE od.trip_id = dd.trip_id AND od.company_id = dd.company_id
                                 AND od.result IN ('REJECTED', 'FAILED', 'NOT_ATTEMPTED')))
                           OR (dd.status IN ('REJECTED', 'NOT_DELIVERED') AND EXISTS (
                               SELECT 1 FROM tms.order_delivery od
                               JOIN tms.document_delivery_order ddo
                                 ON ddo.order_id = od.order_id AND ddo.document_delivery_id = dd.id
                               WHERE od.trip_id = dd.trip_id AND od.company_id = dd.company_id
                                 AND od.result IN ('DELIVERED', 'PARTIAL')))) AS inconsistent,
                       max(dd.recorded_at) AS last_report_at
                FROM tms.document_delivery dd
                WHERE dd.trip_id = t.id AND dd.company_id = t.company_id
            ) d ON true
            """;

    private final JdbcClient jdbc;

    public ShipmentTrackingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> page(Filter filter, int limit, long offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("SELECT * FROM (" + BASE + where + ") r"
                        + " ORDER BY r.actual_dispatch_at DESC, r.shipment_number LIMIT :limit OFFSET :offset")
                .params(params)
                .query(ShipmentTrackingQueries::row)
                .list();
    }

    public long count(Filter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        return jdbc.sql("SELECT count(*) FROM (" + BASE + where + ") r").params(params).query(Long.class).single();
    }

    public Totals totals(Filter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        return jdbc.sql("""
                SELECT count(*) AS shipments,
                       count(*) FILTER (WHERE r.status = 'IN_TRANSIT') AS in_transit,
                       count(*) FILTER (WHERE r.status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE r.pending_count > 0) AS with_pending,
                       coalesce(sum(r.document_count), 0) AS documents_total,
                       coalesce(sum(r.pending_count), 0) AS documents_pending,
                       coalesce(sum(r.delivered_count), 0) AS documents_delivered,
                       coalesce(sum(r.rejected_count), 0) AS documents_rejected,
                       coalesce(sum(r.not_delivered_count), 0) AS documents_not_delivered,
                       coalesce(sum(r.awaiting_return_count), 0) AS awaiting_return,
                       count(*) FILTER (WHERE r.verification_status = 'MISMATCH') AS mismatches
                FROM (""" + BASE + where + ") r")
                .params(params)
                .query((rs, n) -> new Totals(rs.getLong("shipments"), rs.getLong("in_transit"),
                        rs.getLong("completed"), rs.getLong("with_pending"), rs.getLong("documents_total"),
                        rs.getLong("documents_pending"), rs.getLong("documents_delivered"),
                        rs.getLong("documents_rejected"), rs.getLong("documents_not_delivered"),
                        rs.getLong("awaiting_return"), rs.getLong("mismatches")))
                .single();
    }

    /** The stops of a page of trips, in sequence. */
    public List<Stop> stopsOf(UUID companyId, Collection<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT s.id, s.trip_id, s.sequence, s.destination_id, s.execution_status
                FROM tms.trip_stop s
                WHERE s.company_id = :companyId AND s.trip_id IN (:tripIds)
                ORDER BY s.trip_id, s.sequence
                """).param("companyId", companyId).param("tripIds", tripIds)
                .query((rs, n) -> new Stop(rs.getObject("id", UUID.class), rs.getObject("trip_id", UUID.class),
                        rs.getInt("sequence"), rs.getObject("destination_id", UUID.class),
                        rs.getString("execution_status")))
                .list();
    }

    /** (document delivery id, trip, status) for the documents of a page of trips. */
    public record TrackedDocument(UUID id, UUID tripId, String status) {
    }

    public List<TrackedDocument> documentsOf(UUID companyId, Collection<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT dd.id, dd.trip_id, dd.status FROM tms.document_delivery dd
                WHERE dd.company_id = :companyId AND dd.trip_id IN (:tripIds)
                """).param("companyId", companyId).param("tripIds", tripIds)
                .query((rs, n) -> new TrackedDocument(rs.getObject("id", UUID.class),
                        rs.getObject("trip_id", UUID.class), rs.getString("status")))
                .list();
    }

    private static String where(Filter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(
                " WHERE t.company_id = :companyId AND t.status NOT IN ('DRAFT', 'CANCELLED')");
        params.put("companyId", filter.companyId());
        if (filter.dispatchedFrom() != null) {
            where.append(" AND x.actual_dispatch_at >= :dispatchedFrom");
            params.put("dispatchedFrom", filter.dispatchedFrom());
        }
        if (filter.dispatchedBefore() != null) {
            where.append(" AND x.actual_dispatch_at < :dispatchedBefore");
            params.put("dispatchedBefore", filter.dispatchedBefore());
        }
        if (filter.carrierId() != null) {
            where.append(" AND t.carrier_id = :carrierId");
            params.put("carrierId", filter.carrierId());
        }
        if (filter.status() != null) {
            where.append(" AND t.status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.documents() == ShipmentDocumentState.PENDING) {
            where.append(" AND coalesce(d.pending, 0) > 0");
        } else if (filter.documents() == ShipmentDocumentState.COMPLETE) {
            where.append(" AND coalesce(d.pending, 0) = 0");
        }
        if (filter.searchPattern() != null) {
            where.append(" AND (t.shipment_number ILIKE :search ESCAPE '\\'"
                    + " OR x.dispatch_reference ILIKE :search ESCAPE '\\'"
                    + " OR x.vehicle_license_plate ILIKE :search ESCAPE '\\')");
            params.put("search", filter.searchPattern());
        }
        return where.toString();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("shipment_number"),
                TripStatus.valueOf(rs.getString("status")), rs.getObject("planning_date", LocalDate.class),
                rs.getObject("carrier_id", UUID.class), rs.getObject("planning_run_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class), rs.getObject("driver_id", UUID.class),
                rs.getString("dispatch_reference"), rs.getObject("actual_dispatch_at", OffsetDateTime.class),
                rs.getString("verification_status"), rs.getString("vehicle_license_plate"),
                rs.getString("driver_name"), rs.getInt("stops_total"), rs.getInt("stops_resolved"),
                rs.getInt("document_count"), rs.getInt("pending_count"), rs.getInt("delivered_count"),
                rs.getInt("rejected_count"), rs.getInt("not_delivered_count"), rs.getInt("returned_count"),
                rs.getInt("inconsistent_count"), rs.getObject("last_report_at", OffsetDateTime.class));
    }
}
