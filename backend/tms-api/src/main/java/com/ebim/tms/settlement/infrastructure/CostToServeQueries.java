package com.ebim.tms.settlement.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The cost-to-serve aggregates (V68, ADR-026 section 7), over the <b>current</b> run of each trip
 * whose service date is in the range. Reads this module's tables only: what the report groups by
 * was copied onto the run and its rows when they were computed.
 */
@Repository
public class CostToServeQueries {

    /** What the report groups by. Each maps to two fixed SQL expressions, never caller text. */
    public enum GroupBy {
        CUSTOMER("a.customer_label", "a.customer_label"),
        DESTINATION("a.destination_id::text", "a.destination_label"),
        ROUTE("r.route_id::text", "r.route_label");

        private final String key;
        private final String label;

        GroupBy(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /**
     * One group in one currency. Weight and volume are those of the rows that received cost, so a
     * cost per kg divides money by the kilograms that money was spread over.
     */
    public record GroupRow(String key, String label, String currency, long trips, long orders,
            long unallocatedOrders, BigDecimal cost, BigDecimal weightKg, BigDecimal volumeM3) {
    }

    /** The range in one currency, over runs - so trips with no order, or none measurable, are here too. */
    public record TotalsRow(String currency, long trips, BigDecimal cost, BigDecimal allocated,
            long tripsNotAllocated, long unallocatedOrders, long invoiced, long actual, long estimated,
            BigDecimal weightKg) {
    }

    private final JdbcClient jdbcClient;

    public CostToServeQueries(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<GroupRow> groups(UUID companyId, LocalDate from, LocalDate to, GroupBy groupBy) {
        String sql = "SELECT " + groupBy.key + " AS group_key, max(" + groupBy.label + ") AS group_label,"
                + " r.currency,"
                + " count(DISTINCT r.id) AS trips,"
                + " count(DISTINCT a.order_id) FILTER (WHERE a.allocated_amount IS NOT NULL) AS orders,"
                + " count(DISTINCT a.order_id) FILTER (WHERE a.allocated_amount IS NULL) AS unallocated_orders,"
                + " coalesce(sum(a.allocated_amount), 0) AS cost,"
                + " coalesce(sum(a.weight_kg) FILTER (WHERE a.allocated_amount IS NOT NULL), 0) AS weight_kg,"
                + " coalesce(sum(a.volume_m3) FILTER (WHERE a.allocated_amount IS NOT NULL), 0) AS volume_m3"
                + " FROM tms.trip_cost_allocation_run r"
                + " JOIN tms.trip_cost_allocation a ON a.run_id = r.id AND a.company_id = r.company_id"
                + " WHERE r.company_id = :companyId AND r.superseded_at IS NULL"
                + " AND r.service_date BETWEEN :from AND :to"
                + " GROUP BY " + groupBy.key + ", r.currency"
                + " ORDER BY r.currency, cost DESC, group_label NULLS LAST";
        return jdbcClient.sql(sql)
                .param("companyId", companyId)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new GroupRow(rs.getString("group_key"), rs.getString("group_label"),
                        rs.getString("currency").trim(), rs.getLong("trips"), rs.getLong("orders"),
                        rs.getLong("unallocated_orders"), rs.getBigDecimal("cost"), rs.getBigDecimal("weight_kg"),
                        rs.getBigDecimal("volume_m3")))
                .list();
    }

    public List<TotalsRow> totals(UUID companyId, LocalDate from, LocalDate to) {
        return jdbcClient.sql("""
                        SELECT r.currency,
                               count(*) AS trips,
                               sum(r.cost_amount) AS cost,
                               sum(r.allocated_amount) AS allocated,
                               count(*) FILTER (WHERE r.allocated_amount = 0 AND r.cost_amount > 0) AS trips_not_allocated,
                               sum(r.unallocated_order_count) AS unallocated_orders,
                               count(*) FILTER (WHERE r.cost_source = 'INVOICED') AS invoiced,
                               count(*) FILTER (WHERE r.cost_source = 'ACTUAL') AS actual,
                               count(*) FILTER (WHERE r.cost_source = 'ESTIMATED') AS estimated,
                               coalesce((SELECT sum(a.weight_kg)
                                           FROM tms.trip_cost_allocation a
                                           JOIN tms.trip_cost_allocation_run r2 ON r2.id = a.run_id
                                          WHERE a.company_id = :companyId AND r2.superseded_at IS NULL
                                            AND r2.currency = r.currency
                                            AND r2.service_date BETWEEN :from AND :to
                                            AND a.allocated_amount IS NOT NULL), 0) AS weight_kg
                          FROM tms.trip_cost_allocation_run r
                         WHERE r.company_id = :companyId AND r.superseded_at IS NULL
                           AND r.service_date BETWEEN :from AND :to
                         GROUP BY r.currency
                         ORDER BY r.currency
                        """)
                .param("companyId", companyId)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new TotalsRow(rs.getString("currency").trim(), rs.getLong("trips"),
                        rs.getBigDecimal("cost"), rs.getBigDecimal("allocated"), rs.getLong("trips_not_allocated"),
                        rs.getLong("unallocated_orders"), rs.getLong("invoiced"), rs.getLong("actual"),
                        rs.getLong("estimated"), rs.getBigDecimal("weight_kg")))
                .list();
    }
}
