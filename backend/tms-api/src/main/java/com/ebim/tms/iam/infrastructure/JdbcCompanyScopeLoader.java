package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * {@code iam}'s implementation of {@link CompanyScopeLoader}.
 *
 * <p>A separate class rather than a third method on {@link IdentityRepository}, on purpose. That
 * interface documents that it deliberately has no "find company by id": every question it answers
 * is scoped by the caller's own {@code app_user}, so no later change can reach an unscoped one by
 * accident. This <em>is</em> the unscoped question, and it is legitimate exactly once - when a
 * machine credential that already proved possession of its secret needs the company it is
 * permanently bound to. Keeping it in its own class with its own name means the exception is
 * visible in an import statement rather than hidden among the identity queries.
 *
 * <p>The scope it returns has no permissions, which is the second half of the containment: a
 * machine gets a tenant, not a role. See {@link CompanyScopeLoader#loadActiveCompanyScope}.
 */
@Repository
public class JdbcCompanyScopeLoader implements CompanyScopeLoader {

    /**
     * Active company of an active organization. Both {@code active} predicates matter:
     * deactivating a company is how a tenant is switched off, and an integration credential must
     * stop working at the same instant a browser session does.
     */
    private static final String COMPANY_SQL = """
            SELECT c.id        AS company_id,
                   c.code      AS company_code,
                   c.name      AS company_name,
                   c.time_zone AS time_zone,
                   o.id        AS organization_id,
                   o.code      AS organization_code,
                   o.name      AS organization_name
            FROM tms.company c
            JOIN tms.organization o ON o.id = c.organization_id AND o.active
            WHERE c.id = :companyId
              AND c.active
            """;

    private final JdbcClient jdbcClient;

    public JdbcCompanyScopeLoader(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<CompanyScope> loadActiveCompanyScope(UUID companyId) {
        return jdbcClient.sql(COMPANY_SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new CompanyScope(
                        rs.getObject("company_id", UUID.class),
                        rs.getString("company_code"),
                        rs.getString("company_name"),
                        rs.getString("time_zone"),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("organization_code"),
                        rs.getString("organization_name"),
                        // No permissions: a machine holds IntegrationScopes, never the user
                        // authorization vocabulary. Anything guarded by hasAuthority('...:manage')
                        // therefore stays closed to it.
                        Set.of()))
                .optional();
    }
}
