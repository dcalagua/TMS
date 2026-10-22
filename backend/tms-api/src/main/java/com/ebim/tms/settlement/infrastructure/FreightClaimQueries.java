package com.ebim.tms.settlement.infrastructure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two reads of the freight claims (V71) that are aggregates rather than rows: the figures at the
 * top of the claims screen, and which carrier documents carry a penalty line recovering a claim.
 *
 * <p>Settlement's own tables only. Company-scoped in SQL and again by RLS for {@code tms_app}.
 */
@Repository
public class FreightClaimQueries {

    private final JdbcClient jdbc;

    public FreightClaimQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One status and currency of the company's claims: how many, how much, since when. */
    public record StatusTotals(String status, String currency, long count, BigDecimal claimedAmount,
            BigDecimal acceptedAmount, OffsetDateTime oldestOpenedAt) {
    }

    /** Everything not yet closed or cancelled, grouped - a company holds tens of claims, not thousands. */
    public List<StatusTotals> activeTotals(UUID companyId) {
        return jdbc.sql("""
                SELECT status, currency, count(*) AS claims, sum(claimed_amount) AS claimed,
                       sum(accepted_amount) AS accepted, min(opened_at) AS oldest
                  FROM tms.freight_claim
                 WHERE company_id = :companyId AND status NOT IN ('CLOSED', 'CANCELLED')
                 GROUP BY status, currency
                 ORDER BY status, currency
                """)
                .param("companyId", companyId)
                .query((rs, row) -> new StatusTotals(rs.getString("status"), rs.getString("currency"),
                        rs.getLong("claims"), rs.getBigDecimal("claimed"), rs.getBigDecimal("accepted"),
                        rs.getObject("oldest", OffsetDateTime.class)))
                .list();
    }

    /** The opening time of every active claim, for the ageing buckets. */
    public List<OffsetDateTime> activeOpenedAt(UUID companyId) {
        return jdbc.sql("""
                SELECT opened_at FROM tms.freight_claim
                 WHERE company_id = :companyId AND status NOT IN ('CLOSED', 'CANCELLED')
                """)
                .param("companyId", companyId)
                .query((rs, row) -> rs.getObject("opened_at", OffsetDateTime.class))
                .list();
    }

    /**
     * A penalty line that recovers a claim, on a carrier document that has not been rejected.
     *
     * @param lineAmount negative: a penalty is a deduction (V66)
     */
    public record PenaltyApplication(UUID claimId, UUID invoiceId, String invoiceNumber, String invoiceStatus,
            UUID lineId, BigDecimal lineAmount) {
    }

    /**
     * The live penalty lines referencing these claims. A rejected document recovered nothing, so its
     * lines are left out and the claim can be proposed again on the next one.
     */
    public List<PenaltyApplication> penaltyApplications(UUID companyId, Collection<UUID> claimIds) {
        if (claimIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT l.penalty_reference_id AS claim_id, i.id AS invoice_id, i.invoice_number, i.status,
                       l.id AS line_id, l.line_amount
                  FROM tms.carrier_invoice_line l
                  JOIN tms.carrier_invoice i ON i.id = l.carrier_invoice_id AND i.company_id = l.company_id
                 WHERE l.company_id = :companyId
                   AND l.charge_code = 'PENALTY'
                   AND l.penalty_reference_type = 'FREIGHT_CLAIM'
                   AND l.penalty_reference_id IN (:ids)
                   AND i.status <> 'REJECTED'
                 ORDER BY i.created_at, l.line_number
                """)
                .param("companyId", companyId)
                .param("ids", claimIds)
                .query((rs, row) -> new PenaltyApplication(rs.getObject("claim_id", UUID.class),
                        rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                        rs.getString("status"), rs.getObject("line_id", UUID.class),
                        rs.getBigDecimal("line_amount")))
                .list();
    }
}
