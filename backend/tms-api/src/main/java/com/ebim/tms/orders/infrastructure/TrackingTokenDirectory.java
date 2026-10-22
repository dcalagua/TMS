package com.ebim.tms.orders.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one cross-tenant read of the customer tracking link (ADR-029 T4): token hash to
 * {@code (link id, company)}, and nothing else.
 *
 * <p>An anonymous request has no company yet - finding it is the point - so {@code TenantScopedDataSource}
 * leaves its connection on the owner role and RLS filters nothing. That is acceptable for exactly one
 * statement, an equality on a unique 256-bit hash that returns two ids, and for nothing more: the order,
 * its trips and the link's own validity are all read afterwards, as {@code tms_app} bound to the company
 * returned here.
 *
 * <p>Validity (revoked, expired) is deliberately <em>not</em> decided here. It is decided under RLS by the
 * scoped step, so the owner connection never reads a column it does not need.
 */
@Repository
public class TrackingTokenDirectory {

    /** Which link and which tenant a token hash names. */
    public record ResolvedLink(UUID linkId, UUID companyId) {
    }

    private final JdbcClient jdbc;

    public TrackingTokenDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ResolvedLink> resolve(String tokenHash) {
        return jdbc.sql("SELECT id, company_id FROM tms.tracking_link WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query((rs, n) -> new ResolvedLink(rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class)))
                .optional();
    }
}
