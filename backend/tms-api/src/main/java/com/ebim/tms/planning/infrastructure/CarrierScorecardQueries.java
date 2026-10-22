package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TripStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The carrier scorecard's reads ({@code docs/domain/CARRIER_SCORECARD_V1.md}), in plain SQL.
 *
 * <p><b>One statement per family, grouped by carrier, never one per carrier.</b> Trips, stops,
 * deliveries, exceptions, documents and tenders are each one {@code GROUP BY} over the range, so the
 * report costs the same six statements for three carriers or for three hundred. Every statement
 * touches planning's own tables only; carrier names come from {@code CarrierLookupPort}, cost from
 * {@code TripCostAnalyticsPort} and invoices from {@code SettlementKpiPort}.
 *
 * <h2>Who a trip belongs to</h2>
 *
 * <p>{@link #SCOPED_TRIPS} is the one definition, used by every statement so it cannot be restated
 * differently in two places: the owner of the vehicle ({@code trip.carrier_id}), else the carrier
 * that accepted a tender for it ({@code accepted_carrier_id}), else - when a vehicle is assigned and
 * it belongs to nobody - our own fleet ({@value #OWN_FLEET}). A trip with neither a carrier nor a
 * vehicle belongs to nobody yet and is counted apart ({@code carrier_key} null) rather than charged
 * to anyone.
 *
 * <p>Tenders are the exception, by design: an offer is attributed to the carrier it was
 * <em>offered to</em> ({@code trip_tender.carrier_id}), because a refusal is the refusing carrier's
 * record and not the record of whoever ended up running the trip.
 *
 * <p>The range is on {@code trip.planning_date}, both ends inclusive, as in the KPI report, so the
 * two screens describe the same shipments.
 */
@Repository
public class CarrierScorecardQueries {

    /** The key of trips run by our own vehicles: a vehicle with no carrier. */
    public static final String OWN_FLEET = "OWN_FLEET";

    static final String SCOPED_TRIPS = """
            WITH scoped AS (
                SELECT t.id, t.shipment_number, t.status, t.planning_date,
                       t.planned_departure_at, t.actual_departure_at,
                       CASE WHEN COALESCE(t.carrier_id, t.accepted_carrier_id) IS NOT NULL
                                THEN CAST(COALESCE(t.carrier_id, t.accepted_carrier_id) AS text)
                            WHEN t.vehicle_id IS NOT NULL THEN 'OWN_FLEET'
                       END AS carrier_key
                  FROM tms.trip t
                 WHERE t.company_id = :companyId
                   AND t.planning_date BETWEEN :from AND :to
            )
            """;

    private final JdbcClient jdbc;

    public CarrierScorecardQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- trips ---------------------------------------------------------------------------

    /**
     * @param carrierKey          a carrier id as text, {@value #OWN_FLEET}, or null for unattributed
     * @param trips               not cancelled - the denominator of everything about how they ran
     * @param departuresMeasured  trips carrying both a planned and an actual departure
     * @param departuresLate      of those, actual strictly after planned (no grace, as the KPI report)
     */
    public record TripTotals(String carrierKey, long trips, long cancelled, long executed, long completed,
            long departuresMeasured, long departuresLate) {
    }

    public List<TripTotals> tripTotals(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT carrier_key,
                       COUNT(*) FILTER (WHERE status <> 'CANCELLED')                         AS trips,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED')                          AS cancelled,
                       COUNT(*) FILTER (WHERE status IN ('IN_TRANSIT', 'COMPLETED'))         AS executed,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED')                          AS completed,
                       COUNT(*) FILTER (WHERE status <> 'CANCELLED'
                                          AND planned_departure_at IS NOT NULL
                                          AND actual_departure_at IS NOT NULL)               AS departures_measured,
                       COUNT(*) FILTER (WHERE status <> 'CANCELLED'
                                          AND planned_departure_at IS NOT NULL
                                          AND actual_departure_at > planned_departure_at)    AS departures_late
                  FROM scoped
                 GROUP BY carrier_key
                """)
                .param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, row) -> new TripTotals(rs.getString("carrier_key"), rs.getLong("trips"),
                        rs.getLong("cancelled"), rs.getLong("executed"), rs.getLong("completed"),
                        rs.getLong("departures_measured"), rs.getLong("departures_late")))
                .list();
    }

    // --- stops ---------------------------------------------------------------------------

    /**
     * Whether stops were served inside their promised window, judged exactly as
     * {@code TripStopRepository.serviceTotalsForRange} judges it: the window end resolved on the
     * trip's date in the company's zone, compared against the recorded arrival. Measured only where a
     * stop carries both.
     */
    public record StopTotals(String carrierKey, long stops, long windowsMeasured, long windowsMissed) {
    }

    public List<StopTotals> stopTotals(UUID companyId, LocalDate from, LocalDate to, String zone) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT sc.carrier_key,
                       COUNT(*)                                                         AS stops,
                       COUNT(*) FILTER (WHERE s.actual_arrival_at IS NOT NULL
                                          AND s.service_window_end IS NOT NULL)          AS windows_measured,
                       COUNT(*) FILTER (WHERE s.actual_arrival_at IS NOT NULL
                                          AND s.service_window_end IS NOT NULL
                                          AND s.actual_arrival_at >
                                              ((sc.planning_date + s.service_window_end)
                                                  AT TIME ZONE CAST(:zone AS text)))     AS windows_missed
                  FROM scoped sc
                  JOIN tms.trip_stop s ON s.trip_id = sc.id AND s.company_id = :companyId
                 WHERE sc.status <> 'CANCELLED'
                 GROUP BY sc.carrier_key
                """)
                .param("companyId", companyId).param("from", from).param("to", to).param("zone", zone)
                .query((rs, row) -> new StopTotals(rs.getString("carrier_key"), rs.getLong("stops"),
                        rs.getLong("windows_measured"), rs.getLong("windows_missed")))
                .list();
    }

    // --- deliveries ----------------------------------------------------------------------

    /**
     * Delivery outcomes recorded on the carrier's trips. {@code shortfall} is PARTIAL, REJECTED and
     * FAILED - the customer got less than they were owed - and {@code notAttempted} stays apart, as
     * {@code DeliveryResult} keeps it.
     */
    public record DeliveryTotals(String carrierKey, long recorded, long delivered, long shortfall,
            long notAttempted) {
    }

    public List<DeliveryTotals> deliveryTotals(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT sc.carrier_key,
                       COUNT(*)                                                          AS recorded,
                       COUNT(*) FILTER (WHERE d.result = 'DELIVERED')                    AS delivered,
                       COUNT(*) FILTER (WHERE d.result IN ('PARTIAL', 'REJECTED', 'FAILED')) AS shortfall,
                       COUNT(*) FILTER (WHERE d.result = 'NOT_ATTEMPTED')                AS not_attempted
                  FROM scoped sc
                  JOIN tms.order_delivery d ON d.trip_id = sc.id AND d.company_id = :companyId
                 WHERE sc.status <> 'CANCELLED'
                 GROUP BY sc.carrier_key
                """)
                .param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, row) -> new DeliveryTotals(rs.getString("carrier_key"), rs.getLong("recorded"),
                        rs.getLong("delivered"), rs.getLong("shortfall"), rs.getLong("not_attempted")))
                .list();
    }

    // --- exceptions ----------------------------------------------------------------------

    /** Every exception raised on the carrier's trips that ran, open or resolved. */
    public record ExceptionTotals(String carrierKey, long exceptions, long open) {
    }

    public List<ExceptionTotals> exceptionTotals(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT sc.carrier_key,
                       COUNT(*)                                    AS exceptions,
                       COUNT(*) FILTER (WHERE e.status = 'OPEN')   AS open_exceptions
                  FROM scoped sc
                  JOIN tms.trip_exception e ON e.trip_id = sc.id AND e.company_id = :companyId
                 WHERE sc.status <> 'CANCELLED'
                 GROUP BY sc.carrier_key
                """)
                .param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, row) -> new ExceptionTotals(rs.getString("carrier_key"), rs.getLong("exceptions"),
                        rs.getLong("open_exceptions")))
                .list();
    }

    // --- documents (ADR-018) -------------------------------------------------------------

    /**
     * The trip documents the carrier carried (ADR-018): how many were reported delivered, and of
     * those how many came back to the office signed ({@code returned_at}, the "cargo").
     */
    public record DocumentTotals(String carrierKey, long documents, long delivered, long returned) {
    }

    public List<DocumentTotals> documentTotals(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT sc.carrier_key,
                       COUNT(*)                                                       AS documents,
                       COUNT(*) FILTER (WHERE dd.status = 'DELIVERED')                AS delivered,
                       COUNT(*) FILTER (WHERE dd.status = 'DELIVERED'
                                          AND dd.returned_at IS NOT NULL)             AS returned
                  FROM scoped sc
                  JOIN tms.document_delivery dd ON dd.trip_id = sc.id AND dd.company_id = :companyId
                 WHERE sc.status <> 'CANCELLED'
                 GROUP BY sc.carrier_key
                """)
                .param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, row) -> new DocumentTotals(rs.getString("carrier_key"), rs.getLong("documents"),
                        rs.getLong("delivered"), rs.getLong("returned")))
                .list();
    }

    // --- tenders -------------------------------------------------------------------------

    /**
     * Offers made to each carrier on the range's shipments, by the carrier offered.
     *
     * <p>A stored {@code SENT} whose deadline is already behind {@code now} is counted as expired,
     * the reading {@code TripTender.effectiveStatus} gives: a lapse is a moment, not a write somebody
     * still owes. Drafts and offers we withdrew ourselves are not the carrier's record and are left
     * out of {@code offered}.
     *
     * @param averageResponseMinutes sent to answered, over the accepted and rejected ones carrying
     *                               both instants; null when none did
     */
    public record TenderTotals(String carrierKey, long offered, long accepted, long rejected, long expired,
            long awaiting, long timed, BigDecimal averageResponseMinutes) {
    }

    public List<TenderTotals> tenderTotals(UUID companyId, LocalDate from, LocalDate to, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT CAST(te.carrier_id AS text) AS carrier_key,
                       COUNT(*) FILTER (WHERE te.status IN ('SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED')) AS offered,
                       COUNT(*) FILTER (WHERE te.status = 'ACCEPTED')                               AS accepted,
                       COUNT(*) FILTER (WHERE te.status = 'REJECTED')                               AS rejected,
                       COUNT(*) FILTER (WHERE te.status = 'EXPIRED'
                                           OR (te.status = 'SENT' AND te.expires_at <= :now))      AS expired,
                       COUNT(*) FILTER (WHERE te.status = 'SENT'
                                          AND (te.expires_at IS NULL OR te.expires_at > :now))     AS awaiting,
                       COUNT(*) FILTER (WHERE te.status IN ('ACCEPTED', 'REJECTED')
                                          AND te.sent_at IS NOT NULL
                                          AND te.responded_at IS NOT NULL)                         AS timed,
                       ROUND(CAST(AVG(EXTRACT(EPOCH FROM (te.responded_at - te.sent_at)) / 60.0)
                                 FILTER (WHERE te.status IN ('ACCEPTED', 'REJECTED')
                                           AND te.sent_at IS NOT NULL
                                           AND te.responded_at IS NOT NULL) AS numeric), 1)        AS average_minutes
                  FROM tms.trip_tender te
                  JOIN tms.trip t ON t.id = te.trip_id AND t.company_id = te.company_id
                 WHERE te.company_id = :companyId
                   AND t.planning_date BETWEEN :from AND :to
                 GROUP BY te.carrier_id
                """)
                .param("companyId", companyId).param("from", from).param("to", to).param("now", now)
                .query((rs, row) -> new TenderTotals(rs.getString("carrier_key"), rs.getLong("offered"),
                        rs.getLong("accepted"), rs.getLong("rejected"), rs.getLong("expired"),
                        rs.getLong("awaiting"), rs.getLong("timed"), rs.getBigDecimal("average_minutes")))
                .list();
    }

    // --- weight per trip, for cost per kg --------------------------------------------------

    /** The kilograms assigned to one attributed trip that ran, to divide its cost by. */
    public record TripWeight(UUID tripId, String carrierKey, BigDecimal weightKg) {
    }

    public List<TripWeight> tripWeights(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT sc.id, sc.carrier_key, COALESCE(SUM(a.assigned_weight_kg), 0) AS weight_kg
                  FROM scoped sc
                  LEFT JOIN tms.trip_order_assignment a
                         ON a.trip_id = sc.id AND a.company_id = :companyId AND a.status = 'ACTIVE'
                 WHERE sc.status <> 'CANCELLED'
                   AND sc.carrier_key IS NOT NULL
                 GROUP BY sc.id, sc.carrier_key
                """)
                .param("companyId", companyId).param("from", from).param("to", to)
                .query((rs, row) -> new TripWeight(rs.getObject("id", UUID.class), rs.getString("carrier_key"),
                        rs.getBigDecimal("weight_kg")))
                .list();
    }

    // --- drill-down ----------------------------------------------------------------------

    /**
     * One trip of one carrier, with the figures the scorecard counted for it, so a percentage can be
     * checked against the rows it came from.
     *
     * @param departureLate null when the departure was not measured
     */
    public record TripRow(UUID tripId, String shipmentNumber, LocalDate planningDate, TripStatus status,
            OffsetDateTime plannedDepartureAt, OffsetDateTime actualDepartureAt, Boolean departureLate,
            long stops, long windowsMeasured, long windowsMissed, long deliveriesRecorded,
            long deliveriesDelivered, long deliveriesShort, long exceptions) {
    }

    /** How many trips the drill-down has in all, cancelled included, before the page cap. */
    public long countTrips(UUID companyId, LocalDate from, LocalDate to, String carrierKey) {
        return jdbc.sql(SCOPED_TRIPS + "SELECT COUNT(*) FROM scoped WHERE carrier_key = :carrierKey")
                .param("companyId", companyId).param("from", from).param("to", to).param("carrierKey", carrierKey)
                .query(Long.class).single();
    }

    public List<TripRow> trips(UUID companyId, LocalDate from, LocalDate to, String carrierKey, String zone,
            int limit) {
        return jdbc.sql(SCOPED_TRIPS + """
                SELECT sc.id, sc.shipment_number, sc.planning_date, sc.status,
                       sc.planned_departure_at, sc.actual_departure_at,
                       COALESCE(st.stops, 0) AS stops,
                       COALESCE(st.windows_measured, 0) AS windows_measured,
                       COALESCE(st.windows_missed, 0) AS windows_missed,
                       COALESCE(dv.recorded, 0) AS deliveries_recorded,
                       COALESCE(dv.delivered, 0) AS deliveries_delivered,
                       COALESCE(dv.shortfall, 0) AS deliveries_short,
                       COALESCE(ex.exceptions, 0) AS exceptions
                  FROM scoped sc
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS stops,
                               COUNT(*) FILTER (WHERE s.actual_arrival_at IS NOT NULL
                                                  AND s.service_window_end IS NOT NULL) AS windows_measured,
                               COUNT(*) FILTER (WHERE s.actual_arrival_at IS NOT NULL
                                                  AND s.service_window_end IS NOT NULL
                                                  AND s.actual_arrival_at >
                                                      ((sc.planning_date + s.service_window_end)
                                                          AT TIME ZONE CAST(:zone AS text))) AS windows_missed
                          FROM tms.trip_stop s
                         WHERE s.trip_id = sc.id AND s.company_id = :companyId) st ON true
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS recorded,
                               COUNT(*) FILTER (WHERE d.result = 'DELIVERED') AS delivered,
                               COUNT(*) FILTER (WHERE d.result IN ('PARTIAL', 'REJECTED', 'FAILED')) AS shortfall
                          FROM tms.order_delivery d
                         WHERE d.trip_id = sc.id AND d.company_id = :companyId) dv ON true
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS exceptions
                          FROM tms.trip_exception e
                         WHERE e.trip_id = sc.id AND e.company_id = :companyId) ex ON true
                 WHERE sc.carrier_key = :carrierKey
                 ORDER BY sc.planning_date DESC, sc.shipment_number DESC
                 LIMIT :limit
                """)
                .param("companyId", companyId).param("from", from).param("to", to)
                .param("carrierKey", carrierKey).param("zone", zone).param("limit", limit)
                .query(CarrierScorecardQueries::tripRow)
                .list();
    }

    private static TripRow tripRow(ResultSet rs, int row) throws SQLException {
        OffsetDateTime planned = rs.getObject("planned_departure_at", OffsetDateTime.class);
        OffsetDateTime actual = rs.getObject("actual_departure_at", OffsetDateTime.class);
        Boolean late = planned == null || actual == null ? null : actual.isAfter(planned);
        return new TripRow(rs.getObject("id", UUID.class), rs.getString("shipment_number"),
                rs.getObject("planning_date", LocalDate.class), TripStatus.valueOf(rs.getString("status")),
                planned, actual, late, rs.getLong("stops"), rs.getLong("windows_measured"),
                rs.getLong("windows_missed"), rs.getLong("deliveries_recorded"),
                rs.getLong("deliveries_delivered"), rs.getLong("deliveries_short"), rs.getLong("exceptions"));
    }
}
