package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.planning.domain.TripStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The set-shaped reads and writes of document delivery tracking (ADR-018, V57), in plain SQL.
 *
 * <p>Every statement touches planning's own tables only - trips, stops, assignments, external
 * dispatches, order deliveries and the tracking tables. Names that live in other modules (carriers,
 * orders, origins) are resolved by the services through the {@code shared.reference} ports, one
 * batched call per page, as every planning board does.
 *
 * <p>Every statement names the company. For a carrier user the V57 policies narrow the same
 * statements again to that carrier's trips; the {@code carrier_id} predicates written here are the
 * first line, not a reliance on the second.
 */
@Repository
public class DocumentDeliveryQueries {

    /**
     * ADR-018 section 3, the one definition of "the carrier may see this trip": not draft or
     * cancelled, and a current warehouse dispatch matched to it. Used as a fragment by every portal
     * statement so the rule cannot be restated differently in two places.
     */
    private static final String VISIBLE_TRIP = """
             (t.status NOT IN ('DRAFT', 'CANCELLED')
              AND EXISTS (SELECT 1 FROM tms.external_dispatch xv
                          WHERE xv.trip_id = t.id AND xv.company_id = t.company_id AND xv.superseded_at IS NULL))
            """;

    /** The trip's current dispatch document: what physically left, and when. */
    private static final String CURRENT_DISPATCH = """
            LEFT JOIN LATERAL (
                SELECT x.actual_dispatch_at, x.vehicle_license_plate, x.driver_name, x.dispatch_reference
                FROM tms.external_dispatch x
                WHERE x.trip_id = t.id AND x.company_id = t.company_id AND x.superseded_at IS NULL
                ORDER BY x.received_at DESC
                LIMIT 1
            ) x ON true
            """;

    /**
     * ADR-018 section 4: the document state set against the goods' delivery result on the same trip
     * (ADR-009). Flagged, never resolved.
     */
    private static final String INCONSISTENCY = """
            CASE
                WHEN dd.status = 'DELIVERED' AND EXISTS (
                    SELECT 1 FROM tms.order_delivery od
                    JOIN tms.document_delivery_order ddo
                      ON ddo.order_id = od.order_id AND ddo.document_delivery_id = dd.id
                    WHERE od.trip_id = dd.trip_id AND od.company_id = dd.company_id
                      AND od.result IN ('REJECTED', 'FAILED', 'NOT_ATTEMPTED'))
                    THEN 'DOCUMENT_DELIVERED_GOODS_FAILED'
                WHEN dd.status IN ('REJECTED', 'NOT_DELIVERED') AND EXISTS (
                    SELECT 1 FROM tms.order_delivery od
                    JOIN tms.document_delivery_order ddo
                      ON ddo.order_id = od.order_id AND ddo.document_delivery_id = dd.id
                    WHERE od.trip_id = dd.trip_id AND od.company_id = dd.company_id
                      AND od.result IN ('DELIVERED', 'PARTIAL'))
                    THEN 'GOODS_DELIVERED_DOCUMENT_NOT'
            END
            """;

    private final JdbcClient jdbc;

    public DocumentDeliveryQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -----------------------------------------------------------------------------------------
    // Materialisation
    // -----------------------------------------------------------------------------------------

    /** A trip the carrier may see that carries one of the given orders, with that order. */
    public record TripOrder(UUID tripId, UUID orderId) {
    }

    /** The trips a carrier may see that carry the given orders (ADR-018 section 3). */
    public List<TripOrder> visibleTripsCarrying(UUID companyId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT a.trip_id, a.order_id
                FROM tms.trip_order_assignment a
                JOIN tms.trip t ON t.id = a.trip_id AND t.company_id = a.company_id
                WHERE a.company_id = :companyId AND a.status = 'ACTIVE' AND a.order_id IN (:orderIds)
                  AND """ + VISIBLE_TRIP)
                .param("companyId", companyId).param("orderIds", orderIds)
                .query((rs, row) -> new TripOrder(rs.getObject("trip_id", UUID.class),
                        rs.getObject("order_id", UUID.class)))
                .list();
    }

    /** Whether a trip is visible to a carrier at all (ADR-018 section 3), ignoring which carrier. */
    public boolean isVisibleTrip(UUID companyId, UUID tripId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM tms.trip t WHERE t.id = :tripId AND t.company_id = :companyId AND "
                        + VISIBLE_TRIP + ")")
                .param("companyId", companyId).param("tripId", tripId)
                .query(Boolean.class).single();
    }

    /**
     * Inserts the tracking row for a document on a trip if it is not there yet, and links the orders
     * it covers. Idempotent: materialisation runs from two triggers that can overlap.
     */
    public void materialize(UUID companyId, UUID tripId, UUID documentId, String documentType, String documentNumber,
            Collection<UUID> orderIds) {
        jdbc.sql("""
                INSERT INTO tms.document_delivery (company_id, trip_id, document_id, document_type, document_number)
                VALUES (:companyId, :tripId, :documentId, :documentType, :documentNumber)
                ON CONFLICT (trip_id, document_id) DO NOTHING
                """).param("companyId", companyId).param("tripId", tripId).param("documentId", documentId)
                .param("documentType", documentType).param("documentNumber", documentNumber)
                .update();
        for (UUID orderId : orderIds) {
            jdbc.sql("""
                    INSERT INTO tms.document_delivery_order (document_delivery_id, order_id, company_id)
                    SELECT dd.id, :orderId, dd.company_id
                    FROM tms.document_delivery dd
                    WHERE dd.company_id = :companyId AND dd.trip_id = :tripId AND dd.document_id = :documentId
                    ON CONFLICT DO NOTHING
                    """).param("companyId", companyId).param("tripId", tripId).param("documentId", documentId)
                    .param("orderId", orderId)
                    .update();
        }
    }

    /** The orders each tracked document covers, for a page of them. */
    public Map<UUID, List<UUID>> orderIdsOf(UUID companyId, Collection<UUID> documentDeliveryIds) {
        Map<UUID, List<UUID>> byDelivery = new HashMap<>();
        if (documentDeliveryIds.isEmpty()) {
            return byDelivery;
        }
        jdbc.sql("""
                SELECT document_delivery_id, order_id FROM tms.document_delivery_order
                WHERE company_id = :companyId AND document_delivery_id IN (:ids)
                ORDER BY created_at, order_id
                """).param("companyId", companyId).param("ids", documentDeliveryIds)
                .query((ResultSet rs, int row) -> {
                    byDelivery.computeIfAbsent(rs.getObject("document_delivery_id", UUID.class),
                            key -> new ArrayList<>()).add(rs.getObject("order_id", UUID.class));
                    return null;
                }).list();
        return byDelivery;
    }

    // -----------------------------------------------------------------------------------------
    // Portal
    // -----------------------------------------------------------------------------------------

    /** A trip as the carrier's list shows it, with its document counts. */
    public record PortalTripRow(UUID tripId, String shipmentNumber, TripStatus status, LocalDate planningDate,
            UUID planningRunId, UUID vehicleId, UUID driverId, OffsetDateTime dispatchedAt, String dispatchPlate,
            String dispatchDriverName, int stopCount, int documentCount, int pendingCount, int deliveredCount,
            int rejectedCount, int notDeliveredCount, int returnedCount) {
    }

    private static final String PORTAL_TRIP_COLUMNS = """
            SELECT t.id, t.shipment_number, t.status, t.planning_date, t.planning_run_id, t.vehicle_id, t.driver_id,
                   x.actual_dispatch_at, x.vehicle_license_plate, x.driver_name,
                   (SELECT count(*) FROM tms.trip_stop s WHERE s.trip_id = t.id AND s.company_id = t.company_id)
                       AS stop_count,
                   count(dd.id) AS document_count,
                   count(dd.id) FILTER (WHERE dd.status = 'PENDING') AS pending_count,
                   count(dd.id) FILTER (WHERE dd.status = 'DELIVERED') AS delivered_count,
                   count(dd.id) FILTER (WHERE dd.status = 'REJECTED') AS rejected_count,
                   count(dd.id) FILTER (WHERE dd.status = 'NOT_DELIVERED') AS not_delivered_count,
                   count(dd.id) FILTER (WHERE dd.returned_at IS NOT NULL) AS returned_count
            FROM tms.trip t
            """ + CURRENT_DISPATCH + """
            LEFT JOIN tms.document_delivery dd ON dd.trip_id = t.id AND dd.company_id = t.company_id
            """;

    private static final String PORTAL_TRIP_GROUP = """
            GROUP BY t.id, t.shipment_number, t.status, t.planning_date, t.planning_run_id, t.vehicle_id,
                     t.driver_id, x.actual_dispatch_at, x.vehicle_license_plate, x.driver_name
            """;

    /** The carrier's visible trips dispatched since {@code since}, newest first. */
    public List<PortalTripRow> carrierTrips(UUID companyId, UUID carrierId, OffsetDateTime since, boolean pendingOnly,
            int limit) {
        return jdbc.sql(PORTAL_TRIP_COLUMNS + """
                WHERE t.company_id = :companyId AND t.carrier_id = :carrierId
                  AND x.actual_dispatch_at >= :since
                  AND """ + VISIBLE_TRIP + PORTAL_TRIP_GROUP
                + (pendingOnly ? " HAVING count(dd.id) FILTER (WHERE dd.status = 'PENDING') > 0 " : "")
                + " ORDER BY x.actual_dispatch_at DESC, t.shipment_number LIMIT :limit")
                .param("companyId", companyId).param("carrierId", carrierId).param("since", since)
                .param("limit", limit)
                .query(DocumentDeliveryQueries::portalTrip)
                .list();
    }

    /** One trip, only if this carrier may see it (ADR-018 section 3). */
    public Optional<PortalTripRow> carrierTrip(UUID companyId, UUID carrierId, UUID tripId) {
        return jdbc.sql(PORTAL_TRIP_COLUMNS + """
                WHERE t.company_id = :companyId AND t.carrier_id = :carrierId AND t.id = :tripId
                  AND """ + VISIBLE_TRIP + PORTAL_TRIP_GROUP)
                .param("companyId", companyId).param("carrierId", carrierId).param("tripId", tripId)
                .query(DocumentDeliveryQueries::portalTrip)
                .optional();
    }

    private static PortalTripRow portalTrip(ResultSet rs, int row) throws SQLException {
        return new PortalTripRow(rs.getObject("id", UUID.class), rs.getString("shipment_number"),
                TripStatus.valueOf(rs.getString("status")), rs.getObject("planning_date", LocalDate.class),
                rs.getObject("planning_run_id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                rs.getObject("driver_id", UUID.class), rs.getObject("actual_dispatch_at", OffsetDateTime.class),
                rs.getString("vehicle_license_plate"), rs.getString("driver_name"), rs.getInt("stop_count"),
                rs.getInt("document_count"), rs.getInt("pending_count"), rs.getInt("delivered_count"),
                rs.getInt("rejected_count"), rs.getInt("not_delivered_count"), rs.getInt("returned_count"));
    }

    // -----------------------------------------------------------------------------------------
    // Office monitor
    // -----------------------------------------------------------------------------------------

    /**
     * The monitor's filter, already resolved: instants in the company's zone, a search pattern
     * already escaped. Null fields do not filter.
     */
    public record MonitorFilter(UUID companyId, OffsetDateTime dispatchedFrom, OffsetDateTime dispatchedBefore,
            UUID carrierId, DocumentDeliveryStatus status, Boolean returned, String searchPattern, UUID id) {

        public MonitorFilter withoutStatus() {
            return new MonitorFilter(companyId, dispatchedFrom, dispatchedBefore, carrierId, null, null,
                    searchPattern, id);
        }
    }

    public record MonitorRow(UUID id, UUID tripId, String shipmentNumber, TripStatus tripStatus,
            OffsetDateTime dispatchedAt, UUID carrierId, UUID vehicleId, UUID driverId, String dispatchPlate,
            String dispatchDriverName, UUID documentId, String documentType, String documentNumber,
            DocumentDeliveryStatus status, DocumentDeliveryReason reasonCode, String remark,
            OffsetDateTime reportedAt, OffsetDateTime recordedAt, String recordedByLabel, OffsetDateTime returnedAt,
            String returnedByLabel, String returnRemark, int evidenceCount, String inconsistency) {
    }

    public record StatusTotals(UUID carrierId, long total, long pending, long delivered, long rejected,
            long notDelivered, long returned, long awaitingReturn, long inconsistent) {
    }

    private static final String MONITOR_BASE = """
            FROM tms.document_delivery dd
            JOIN tms.trip t ON t.id = dd.trip_id AND t.company_id = dd.company_id
            """ + CURRENT_DISPATCH;

    public List<MonitorRow> monitorPage(MonitorFilter filter, int limit, long offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("""
                SELECT dd.id, dd.trip_id, t.shipment_number, t.status AS trip_status, x.actual_dispatch_at,
                       t.carrier_id, t.vehicle_id, t.driver_id, x.vehicle_license_plate, x.driver_name,
                       dd.document_id, dd.document_type, dd.document_number, dd.status, dd.reason_code, dd.remark,
                       dd.reported_at, dd.recorded_at, dd.recorded_by_label, dd.returned_at, dd.returned_by_label,
                       dd.return_remark,
                       (SELECT count(*) FROM tms.document_delivery_evidence e
                        WHERE e.document_delivery_id = dd.id AND e.company_id = dd.company_id) AS evidence_count,
                """ + INCONSISTENCY + " AS inconsistency " + MONITOR_BASE + where + """
                 ORDER BY x.actual_dispatch_at DESC NULLS LAST, t.shipment_number, dd.document_type, dd.document_number
                 LIMIT :limit OFFSET :offset
                """)
                .params(params)
                .query(DocumentDeliveryQueries::monitorRow)
                .list();
    }

    public long monitorCount(MonitorFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        return jdbc.sql("SELECT count(*) " + MONITOR_BASE + where).params(params).query(Long.class).single();
    }

    /** Totals by carrier for the filter (status and returned ignored: the totals are those). */
    public List<StatusTotals> totalsByCarrier(MonitorFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter.withoutStatus(), params);
        return jdbc.sql("""
                WITH base AS (
                    SELECT t.carrier_id, dd.status, dd.returned_at, """ + INCONSISTENCY + " AS inconsistency "
                + MONITOR_BASE + where + """
                )
                SELECT carrier_id,
                       count(*) AS total,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'DELIVERED') AS delivered,
                       count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE status = 'NOT_DELIVERED') AS not_delivered,
                       count(*) FILTER (WHERE returned_at IS NOT NULL) AS returned,
                       count(*) FILTER (WHERE status <> 'PENDING' AND returned_at IS NULL) AS awaiting_return,
                       count(*) FILTER (WHERE inconsistency IS NOT NULL) AS inconsistent
                FROM base
                GROUP BY carrier_id
                ORDER BY total DESC
                """)
                .params(params)
                .query((rs, row) -> new StatusTotals(rs.getObject("carrier_id", UUID.class), rs.getLong("total"),
                        rs.getLong("pending"), rs.getLong("delivered"), rs.getLong("rejected"),
                        rs.getLong("not_delivered"), rs.getLong("returned"), rs.getLong("awaiting_return"),
                        rs.getLong("inconsistent")))
                .list();
    }

    private static String where(MonitorFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE dd.company_id = :companyId");
        params.put("companyId", filter.companyId());
        if (filter.id() != null) {
            where.append(" AND dd.id = :id");
            params.put("id", filter.id());
        }
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
            where.append(" AND dd.status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.returned() != null) {
            where.append(filter.returned() ? " AND dd.returned_at IS NOT NULL" : " AND dd.returned_at IS NULL");
        }
        if (filter.searchPattern() != null) {
            where.append(" AND (dd.document_number ILIKE :search ESCAPE '\\' OR t.shipment_number ILIKE :search "
                    + "ESCAPE '\\')");
            params.put("search", filter.searchPattern());
        }
        return where.toString();
    }

    private static MonitorRow monitorRow(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("reason_code");
        return new MonitorRow(rs.getObject("id", UUID.class), rs.getObject("trip_id", UUID.class),
                rs.getString("shipment_number"), TripStatus.valueOf(rs.getString("trip_status")),
                rs.getObject("actual_dispatch_at", OffsetDateTime.class), rs.getObject("carrier_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class), rs.getObject("driver_id", UUID.class),
                rs.getString("vehicle_license_plate"), rs.getString("driver_name"),
                rs.getObject("document_id", UUID.class), rs.getString("document_type"),
                rs.getString("document_number"), DocumentDeliveryStatus.valueOf(rs.getString("status")),
                reason == null ? null : DocumentDeliveryReason.valueOf(reason), rs.getString("remark"),
                rs.getObject("reported_at", OffsetDateTime.class), rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("recorded_by_label"), rs.getObject("returned_at", OffsetDateTime.class),
                rs.getString("returned_by_label"), rs.getString("return_remark"), rs.getInt("evidence_count"),
                rs.getString("inconsistency"));
    }
}
