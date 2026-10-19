package com.ebim.tms.settlement.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The accrual reports that are aggregates rather than rows (V65, ADR-023). Native SQL over
 * settlement's own tables only, company predicate first.
 */
@Repository
public class AccrualReportQueries {

    /**
     * The balance of each accrual at the cut-off is the sum of its movements effective before it - the
     * movements, not the running totals, so a past date reads what was open then. The birth is
     * effective on the service date (an accrual made late by "accrue now" still belongs to the day the
     * service was rendered); every later movement on the instant it was booked. Only a positive
     * balance is open: a negative one is an invoice above the accrual, a variance rather than a debt.
     */
    private static final String AGING_SQL = """
            WITH balance_at AS (
                SELECT a.id, a.carrier_id, a.currency, a.service_date, SUM(e.amount) AS balance
                  FROM tms.freight_accrual a
                  JOIN tms.freight_accrual_entry e
                    ON e.accrual_id = a.id AND e.company_id = a.company_id
                 WHERE a.company_id = :companyId
                   AND a.service_date <= CAST(:asOf AS date)
                   AND (e.entry_type = 'ACCRUE' OR e.created_at < :cutoff)
                 GROUP BY a.id, a.carrier_id, a.currency, a.service_date
            )
            SELECT carrier_id,
                   currency,
                   COUNT(*) AS accruals,
                   COALESCE(SUM(balance) FILTER (WHERE CAST(:asOf AS date) - service_date <= 30), 0)            AS d0_30,
                   COALESCE(SUM(balance) FILTER (WHERE CAST(:asOf AS date) - service_date BETWEEN 31 AND 60), 0) AS d31_60,
                   COALESCE(SUM(balance) FILTER (WHERE CAST(:asOf AS date) - service_date BETWEEN 61 AND 90), 0) AS d61_90,
                   COALESCE(SUM(balance) FILTER (WHERE CAST(:asOf AS date) - service_date > 90), 0)             AS d90,
                   SUM(balance) AS total
              FROM balance_at
             WHERE balance > 0
             GROUP BY carrier_id, currency
             ORDER BY currency, carrier_id
            """;

    private final JdbcClient jdbcClient;

    public AccrualReportQueries(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AgingRow> aging(UUID companyId, LocalDate asOf, OffsetDateTime cutoff) {
        return jdbcClient.sql(AGING_SQL)
                .param("companyId", companyId)
                .param("asOf", asOf)
                .param("cutoff", cutoff)
                .query((rs, rowNum) -> new AgingRow(
                        rs.getObject("carrier_id", UUID.class),
                        rs.getString("currency"),
                        rs.getLong("accruals"),
                        rs.getBigDecimal("d0_30"),
                        rs.getBigDecimal("d31_60"),
                        rs.getBigDecimal("d61_90"),
                        rs.getBigDecimal("d90"),
                        rs.getBigDecimal("total")))
                .list();
    }

    public record AgingRow(UUID carrierId, String currency, long accruals, BigDecimal days0To30,
            BigDecimal days31To60, BigDecimal days61To90, BigDecimal over90, BigDecimal total) {
    }
}
