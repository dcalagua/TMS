package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.AllocationCostSource;
import com.ebim.tms.settlement.domain.AllocationTrigger;
import com.ebim.tms.settlement.domain.CostAllocationLine;
import com.ebim.tms.settlement.domain.CostAllocationRun;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.UnallocatedReason;
import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The allocation runs and their rows (V68, ADR-026). Plain SQL: both tables are written once and
 * read in bulk, and the one update a run ever takes - being superseded - is a single statement the
 * column-level grant and the guard trigger both check.
 */
@Repository
public class CostAllocationRepository {

    private static final String RUN_COLUMNS = """
            id, trip_id, version, basis, cost_source, currency, cost_amount, allocated_amount, order_count,
            unallocated_order_count, unallocated_reason, service_date, shipment_number, carrier_id, route_id,
            route_label, computed_at, computed_by, trigger, superseded_at
            """;

    private final JdbcClient jdbcClient;

    public CostAllocationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The current run of the trip, locked so two recalculations of it take turns. */
    public Optional<CostAllocationRun> findCurrentForUpdate(UUID companyId, UUID tripId) {
        return jdbcClient.sql("SELECT " + RUN_COLUMNS + " FROM tms.trip_cost_allocation_run"
                        + " WHERE company_id = :companyId AND trip_id = :tripId AND superseded_at IS NULL FOR UPDATE")
                .param("companyId", companyId)
                .param("tripId", tripId)
                .query((rs, rowNum) -> run(rs))
                .optional();
    }

    /** The current run of each of these trips that has one. */
    public Map<UUID, CostAllocationRun> findCurrent(UUID companyId, Collection<UUID> tripIds) {
        Map<UUID, CostAllocationRun> byTrip = new HashMap<>();
        if (tripIds.isEmpty()) {
            return byTrip;
        }
        jdbcClient.sql("SELECT " + RUN_COLUMNS + " FROM tms.trip_cost_allocation_run"
                        + " WHERE company_id = :companyId AND trip_id IN (:tripIds) AND superseded_at IS NULL")
                .param("companyId", companyId)
                .param("tripIds", List.copyOf(tripIds))
                .query((rs, rowNum) -> run(rs))
                .list()
                .forEach(run -> byTrip.put(run.tripId(), run));
        return byTrip;
    }

    /** Every version of the trip, newest first. */
    public List<CostAllocationRun> findVersions(UUID companyId, UUID tripId) {
        return jdbcClient.sql("SELECT " + RUN_COLUMNS + " FROM tms.trip_cost_allocation_run"
                        + " WHERE company_id = :companyId AND trip_id = :tripId ORDER BY version DESC")
                .param("companyId", companyId)
                .param("tripId", tripId)
                .query((rs, rowNum) -> run(rs))
                .list();
    }

    public Optional<CostAllocationRun> findVersion(UUID companyId, UUID tripId, int version) {
        return jdbcClient.sql("SELECT " + RUN_COLUMNS + " FROM tms.trip_cost_allocation_run"
                        + " WHERE company_id = :companyId AND trip_id = :tripId AND version = :version")
                .param("companyId", companyId)
                .param("tripId", tripId)
                .param("version", version)
                .query((rs, rowNum) -> run(rs))
                .optional();
    }

    /** The highest version the trip ever had, 0 for none. */
    public int lastVersion(UUID companyId, UUID tripId) {
        return jdbcClient.sql("SELECT coalesce(max(version), 0) FROM tms.trip_cost_allocation_run"
                        + " WHERE company_id = :companyId AND trip_id = :tripId")
                .param("companyId", companyId)
                .param("tripId", tripId)
                .query(Integer.class)
                .single();
    }

    /** The only update a run ever takes. */
    public int supersede(UUID companyId, UUID runId, OffsetDateTime at) {
        return jdbcClient.sql("UPDATE tms.trip_cost_allocation_run SET superseded_at = :at"
                        + " WHERE company_id = :companyId AND id = :runId AND superseded_at IS NULL")
                .param("companyId", companyId)
                .param("runId", runId)
                .param("at", at)
                .update();
    }

    public void insert(UUID companyId, CostAllocationRun run, List<CostAllocationLine> lines) {
        jdbcClient.sql("""
                        INSERT INTO tms.trip_cost_allocation_run (id, company_id, trip_id, version, basis, cost_source,
                            currency, cost_amount, allocated_amount, order_count, unallocated_order_count,
                            unallocated_reason, service_date, shipment_number, carrier_id, route_id, route_label,
                            computed_at, computed_by, trigger)
                        VALUES (:id, :companyId, :tripId, :version, :basis, :costSource, :currency, :costAmount,
                            :allocatedAmount, :orderCount, :unallocatedOrderCount, :unallocatedReason, :serviceDate,
                            :shipmentNumber, :carrierId, :routeId, :routeLabel, :computedAt, :computedBy, :trigger)
                        """)
                .param("id", run.id())
                .param("companyId", companyId)
                .param("tripId", run.tripId())
                .param("version", run.version())
                .param("basis", run.basis().name())
                .param("costSource", run.costSource().name())
                .param("currency", run.currency())
                .param("costAmount", run.costAmount())
                .param("allocatedAmount", run.allocatedAmount())
                .param("orderCount", run.orderCount())
                .param("unallocatedOrderCount", run.unallocatedOrderCount())
                .param("unallocatedReason", run.unallocatedReason() == null ? null : run.unallocatedReason().name())
                .param("serviceDate", run.serviceDate())
                .param("shipmentNumber", run.shipmentNumber())
                .param("carrierId", run.carrierId())
                .param("routeId", run.routeId())
                .param("routeLabel", run.routeLabel())
                .param("computedAt", run.computedAt())
                .param("computedBy", run.computedBy())
                .param("trigger", run.trigger().name())
                .update();
        for (CostAllocationLine line : lines) {
            jdbcClient.sql("""
                            INSERT INTO tms.trip_cost_allocation (company_id, run_id, trip_id, order_id, assignment_id,
                                order_number, customer_label, destination_id, destination_label, weight_kg, volume_m3,
                                pallets, floor_area_m2, basis_quantity, share, allocated_amount, reason)
                            VALUES (:companyId, :runId, :tripId, :orderId, :assignmentId, :orderNumber, :customerLabel,
                                :destinationId, :destinationLabel, :weightKg, :volumeM3, :pallets, :floorAreaM2,
                                :basisQuantity, :share, :allocatedAmount, :reason)
                            """)
                    .param("companyId", companyId)
                    .param("runId", run.id())
                    .param("tripId", run.tripId())
                    .param("orderId", line.orderId())
                    .param("assignmentId", line.assignmentId())
                    .param("orderNumber", line.orderNumber())
                    .param("customerLabel", line.customerLabel())
                    .param("destinationId", line.destinationId())
                    .param("destinationLabel", line.destinationLabel())
                    .param("weightKg", line.weightKg())
                    .param("volumeM3", line.volumeM3())
                    .param("pallets", line.pallets())
                    .param("floorAreaM2", line.floorAreaM2())
                    .param("basisQuantity", line.basisQuantity())
                    .param("share", line.share())
                    .param("allocatedAmount", line.allocatedAmount())
                    .param("reason", line.reason())
                    .update();
        }
    }

    public List<CostAllocationLine> findLines(UUID companyId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT order_id, assignment_id, order_number, customer_label, destination_id, destination_label,
                               weight_kg, volume_m3, pallets, floor_area_m2, basis_quantity, share, allocated_amount, reason
                          FROM tms.trip_cost_allocation
                         WHERE company_id = :companyId AND run_id = :runId
                         ORDER BY allocated_amount DESC NULLS LAST, order_number
                        """)
                .param("companyId", companyId)
                .param("runId", runId)
                .query((rs, rowNum) -> new CostAllocationLine(
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("assignment_id", UUID.class),
                        rs.getString("order_number"),
                        rs.getString("customer_label"),
                        rs.getObject("destination_id", UUID.class),
                        rs.getString("destination_label"),
                        rs.getBigDecimal("weight_kg"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getBigDecimal("pallets"),
                        rs.getBigDecimal("floor_area_m2"),
                        rs.getBigDecimal("basis_quantity"),
                        rs.getBigDecimal("share"),
                        rs.getBigDecimal("allocated_amount"),
                        rs.getString("reason")))
                .list();
    }

    /**
     * What the carrier's authorised documents billed for the trip, by currency: the sum of their lines
     * for it - net of IGV once a document is broken down (ADR-024), credit notes subtracting and debit
     * notes adding. Empty when no authorised document bills the trip.
     */
    public Map<String, BigDecimal> invoicedByCurrency(UUID companyId, UUID tripId) {
        Map<String, BigDecimal> byCurrency = new TreeMap<>();
        jdbcClient.sql("""
                        SELECT i.currency, sum(l.line_amount) AS amount
                          FROM tms.carrier_invoice_line l
                          JOIN tms.carrier_invoice i ON i.id = l.carrier_invoice_id AND i.company_id = l.company_id
                         WHERE l.company_id = :companyId AND l.trip_id = :tripId AND i.status IN (:statuses)
                         GROUP BY i.currency
                        """)
                .param("companyId", companyId)
                .param("tripId", tripId)
                .param("statuses", InvoiceStatus.AUTHORISED.stream().map(Enum::name).toList())
                .query((rs, rowNum) -> Map.entry(rs.getString("currency").trim(), rs.getBigDecimal("amount")))
                .list()
                .forEach(entry -> byCurrency.put(entry.getKey(), entry.getValue()));
        return byCurrency;
    }

    private static CostAllocationRun run(ResultSet rs) throws SQLException {
        String reason = rs.getString("unallocated_reason");
        return new CostAllocationRun(
                rs.getObject("id", UUID.class),
                rs.getObject("trip_id", UUID.class),
                rs.getInt("version"),
                CostAllocationBasis.valueOf(rs.getString("basis")),
                AllocationCostSource.valueOf(rs.getString("cost_source")),
                rs.getString("currency").trim(),
                rs.getBigDecimal("cost_amount"),
                rs.getBigDecimal("allocated_amount"),
                rs.getInt("order_count"),
                rs.getInt("unallocated_order_count"),
                reason == null ? null : UnallocatedReason.valueOf(reason),
                rs.getObject("service_date", LocalDate.class),
                rs.getString("shipment_number"),
                rs.getObject("carrier_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getString("route_label"),
                rs.getObject("computed_at", OffsetDateTime.class),
                rs.getObject("computed_by", UUID.class),
                AllocationTrigger.valueOf(rs.getString("trigger")),
                rs.getObject("superseded_at", OffsetDateTime.class));
    }
}
