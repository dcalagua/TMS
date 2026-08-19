package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.iam.domain.AppUserProfile;
import com.ebim.tms.iam.domain.CompanyPermissionRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The identity and tenancy queries, in explicit SQL.
 *
 * <p>Plain JDBC rather than JPA for this one path, on purpose. It runs on every authenticated
 * request, so it must be exactly two statements with no lazy loading and no accidental
 * {@code N+1}; and it is the query that decides who may act where, so it should be readable in
 * full, in one place, by someone auditing the tenancy rules.
 *
 * <p>Business modules from Step 05 onwards use JPA as the architecture of record specifies.
 */
@Repository
public class JdbcIdentityRepository implements IdentityRepository {

    private static final String PROFILE_SQL = """
            SELECT id, auth_user_id, email, full_name
            FROM tms.app_user
            WHERE auth_user_id = :authUserId
              AND active
            """;

    /**
     * The company access query. Every join carries a rule:
     *
     * <ul>
     *   <li>{@code m.active}, {@code o.active}, {@code c.active}: a deactivated membership,
     *       organization or company grants nothing. Deactivation is how access is revoked,
     *       because rows are never deleted (migration V2).</li>
     *   <li>{@code m.company_id IS NULL OR m.company_id = c.id}: an organization-wide
     *       membership expands to every company of that organization; a company-scoped one
     *       reaches exactly its own. The composite foreign key on {@code membership} already
     *       guarantees the company belongs to the organization.</li>
     *   <li>{@code m.company_id IS NULL OR r.scope_level <> 'ORGANIZATION'}: a role meant for
     *       organization-wide use grants nothing when it is attached to a company-scoped
     *       membership. The database cannot express that pairing, so it is enforced here -
     *       it is the rule Step 02 handed over as "a Java rule; no trigger enforces it".</li>
     * </ul>
     *
     * <p>The driving index is {@code ix_membership_app_user_active}.
     */
    private static final String COMPANY_PERMISSIONS_SQL = """
            SELECT c.id            AS company_id,
                   c.code          AS company_code,
                   c.name          AS company_name,
                   c.time_zone     AS time_zone,
                   o.id            AS organization_id,
                   o.code          AS organization_code,
                   o.name          AS organization_name,
                   p.code          AS permission_code
            FROM tms.membership m
            JOIN tms.organization o
              ON o.id = m.organization_id AND o.active
            JOIN tms.company c
              ON c.organization_id = o.id AND c.active
             AND (m.company_id IS NULL OR m.company_id = c.id)
            JOIN tms.membership_role mr
              ON mr.membership_id = m.id
            JOIN tms.role r
              ON r.id = mr.role_id AND r.active
             AND (m.company_id IS NULL OR r.scope_level <> 'ORGANIZATION')
            JOIN tms.role_permission rp
              ON rp.role_id = r.id
            JOIN tms.permission p
              ON p.id = rp.permission_id
            WHERE m.app_user_id = :appUserId
              AND m.active
            ORDER BY o.code, c.code, p.code
            """;

    private final JdbcClient jdbcClient;

    public JdbcIdentityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<AppUserProfile> findActiveProfileByAuthUserId(UUID authUserId) {
        return jdbcClient.sql(PROFILE_SQL)
                .param("authUserId", authUserId)
                .query((rs, rowNum) -> new AppUserProfile(
                        rs.getObject("id", UUID.class),
                        rs.getObject("auth_user_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name")))
                .optional();
    }

    @Override
    public List<CompanyPermissionRow> findCompanyPermissions(UUID appUserId) {
        return jdbcClient.sql(COMPANY_PERMISSIONS_SQL)
                .param("appUserId", appUserId)
                .query((rs, rowNum) -> new CompanyPermissionRow(
                        rs.getObject("company_id", UUID.class),
                        rs.getString("company_code"),
                        rs.getString("company_name"),
                        rs.getString("time_zone"),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("organization_code"),
                        rs.getString("organization_name"),
                        rs.getString("permission_code")))
                .list();
    }
}
