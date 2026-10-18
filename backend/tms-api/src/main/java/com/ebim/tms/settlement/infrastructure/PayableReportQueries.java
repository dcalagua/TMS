package com.ebim.tms.settlement.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The aging and the due list of carrier payables (V67, ADR-023 section 2), in SQL because both are
 * sums over every open invoice of a company and a page of entities would be the wrong tool.
 *
 * <p>The open balance is {@code PayableBalance}'s formula, written once more here: total, minus the
 * approved credit notes, plus the approved debit notes, minus detraction and retention, minus what was
 * paid. The integration test checks that the report and {@code GET /settlement/payables/{id}} agree on
 * the same invoice, so the two copies cannot drift unnoticed. Only invoices
 * ({@code 01}) are payables; a note adjusts its invoice. Only a positive balance is owed.
 *
 * <p>Carrier names are not joined here: {@code tms.carrier} is {@code fleet}'s, and the caller resolves
 * them through {@code CarrierLookupPort}.
 */
@Repository
public class PayableReportQueries {

    /** Every status an authorised document can be in ({@code InvoiceStatus.AUTHORISED}). */
    private static final String AUTHORISED = "('APPROVED', 'EXPORTED', 'SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD')";

    private static final String OPEN_PAYABLES = """
            WITH open_payable AS (
                SELECT i.id, i.invoice_number, i.carrier_id, i.currency, i.status, i.invoice_date, i.due_date,
                       i.paid_amount,
                       i.total_amount
                         - COALESCE((SELECT sum(n.total_amount) FROM tms.carrier_invoice n
                                      WHERE n.company_id = i.company_id AND n.referenced_invoice_id = i.id
                                        AND n.document_type = '07' AND n.status IN %1$s), 0)
                         + COALESCE((SELECT sum(n.total_amount) FROM tms.carrier_invoice n
                                      WHERE n.company_id = i.company_id AND n.referenced_invoice_id = i.id
                                        AND n.document_type = '08' AND n.status IN %1$s), 0)
                         - COALESCE(i.detraction_amount, 0)
                         - COALESCE(i.retention_amount, 0) AS payable_amount
                  FROM tms.carrier_invoice i
                 WHERE i.company_id = :companyId
                   AND i.document_type = '01'
                   AND i.status IN ('APPROVED', 'EXPORTED', 'SCHEDULED', 'PARTIALLY_PAID', 'ON_HOLD')
            ), owed AS (
                SELECT p.*, p.payable_amount - p.paid_amount AS open_balance
                  FROM open_payable p
            )
            """.formatted(AUTHORISED);

    private static final String AGING_SQL = OPEN_PAYABLES + """
            SELECT carrier_id, currency,
                   CASE WHEN due_date IS NULL THEN 'NO_DUE_DATE'
                        WHEN due_date >= :asOf THEN 'NOT_DUE'
                        WHEN :asOf - due_date <= 30 THEN 'DAYS_1_30'
                        WHEN :asOf - due_date <= 60 THEN 'DAYS_31_60'
                        WHEN :asOf - due_date <= 90 THEN 'DAYS_61_90'
                        ELSE 'DAYS_OVER_90' END AS bucket,
                   sum(open_balance) AS amount,
                   count(*) AS invoices
              FROM owed
             WHERE open_balance > 0
             GROUP BY 1, 2, 3
             ORDER BY 2, 1, 3
            """;

    private static final String DUE_SQL = OPEN_PAYABLES + """
            SELECT id, invoice_number, carrier_id, currency, status, invoice_date, due_date, paid_amount,
                   payable_amount, open_balance
              FROM owed
             WHERE open_balance > 0
               AND due_date IS NOT NULL
               AND due_date <= :until
               AND (CAST(:carrierId AS uuid) IS NULL OR carrier_id = CAST(:carrierId AS uuid))
             ORDER BY due_date, invoice_number, id
             LIMIT :limit
            """;

    private static final String SUMMARY_SQL = OPEN_PAYABLES + """
            SELECT currency,
                   count(*) FILTER (WHERE due_date < :today) AS overdue_count,
                   COALESCE(sum(open_balance) FILTER (WHERE due_date < :today), 0) AS overdue_amount,
                   count(*) FILTER (WHERE due_date BETWEEN :today AND :until) AS due_soon_count,
                   COALESCE(sum(open_balance) FILTER (WHERE due_date BETWEEN :today AND :until), 0) AS due_soon_amount
              FROM owed
             WHERE open_balance > 0
               AND due_date IS NOT NULL
             GROUP BY currency
             ORDER BY currency
            """;

    public record AgingRow(UUID carrierId, String currency, String bucket, BigDecimal amount, long invoices) {
    }

    public record DueRow(UUID invoiceId, String invoiceNumber, UUID carrierId, String currency, String status,
            LocalDate invoiceDate, LocalDate dueDate, BigDecimal paidAmount, BigDecimal payableAmount,
            BigDecimal openBalance) {
    }

    public record SummaryRow(String currency, long overdueCount, BigDecimal overdueAmount, long dueSoonCount,
            BigDecimal dueSoonAmount) {
    }

    private final JdbcClient jdbcClient;

    public PayableReportQueries(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AgingRow> aging(UUID companyId, LocalDate asOf) {
        return jdbcClient.sql(AGING_SQL)
                .param("companyId", companyId)
                .param("asOf", asOf)
                .query((rs, rowNum) -> new AgingRow(rs.getObject("carrier_id", UUID.class), rs.getString("currency"),
                        rs.getString("bucket"), rs.getBigDecimal("amount"), rs.getLong("invoices")))
                .list();
    }

    public List<DueRow> due(UUID companyId, LocalDate until, UUID carrierId, int limit) {
        return jdbcClient.sql(DUE_SQL)
                .param("companyId", companyId)
                .param("until", until)
                .param("carrierId", carrierId == null ? null : carrierId.toString())
                .param("limit", limit)
                .query((rs, rowNum) -> new DueRow(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                        rs.getObject("carrier_id", UUID.class), rs.getString("currency"), rs.getString("status"),
                        rs.getObject("invoice_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("paid_amount"), rs.getBigDecimal("payable_amount"),
                        rs.getBigDecimal("open_balance")))
                .list();
    }

    public List<SummaryRow> summary(UUID companyId, LocalDate today, LocalDate until) {
        return jdbcClient.sql(SUMMARY_SQL)
                .param("companyId", companyId)
                .param("today", today)
                .param("until", until)
                .query((rs, rowNum) -> new SummaryRow(rs.getString("currency"), rs.getLong("overdue_count"),
                        rs.getBigDecimal("overdue_amount"), rs.getLong("due_soon_count"),
                        rs.getBigDecimal("due_soon_amount")))
                .list();
    }
}
