package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.iam.domain.AdministeredUserRow;
import com.ebim.tms.iam.domain.RoleCatalogueRow;
import com.ebim.tms.shared.api.PageQuery;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The people half of tenant administration: {@code tms.app_user}, {@code tms.membership} and
 * {@code tms.membership_role}, read and written for one company.
 *
 * <p>Plain JDBC for the reason {@link CompanyAdministrationRepository} states.
 *
 * <p><b>Two tenant rules live in this class and nowhere else, so read them here.</b> V13 section 5
 * gives these three tables the {@code USING (true)} policy rather than a tenant policy, because
 * authentication has to read them <em>before</em> a company exists. There is therefore no database
 * predicate underneath any statement below, and every one of them carries its own:
 *
 * <ol>
 *   <li>reads are pinned to {@code m.organization_id = :organizationId} <em>and</em>
 *       {@code (m.company_id = :companyId OR m.company_id IS NULL)}, so a list can only ever
 *       contain memberships that reach the company being administered;</li>
 *   <li>writes to a membership are pinned to {@code company_id = :companyId}, which is not merely a
 *       repetition of the read - it is what makes an organization-wide membership
 *       ({@code company_id IS NULL}) unwritable from a company screen, at the statement level,
 *       independently of the service check that also refuses it.</li>
 * </ol>
 */
@Repository
public class UserAdministrationRepository {

    /**
     * The sortable properties of the people list, mapped to the columns they mean. An allow-list
     * because a sort term reaches an {@code ORDER BY}, which no parameter can carry - the same rule
     * {@link PageQuery#sortTerms} exists to enforce, restated here in the one place that turns a
     * validated property into SQL text.
     */
    static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "email", "u.email",
            "fullName", "u.full_name",
            "active", "m.active",
            "createdAt", "m.created_at");

    private static final String USER_COLUMNS = """
            SELECT m.id                     AS membership_id,
                   u.id                     AS app_user_id,
                   u.email                  AS email,
                   u.full_name              AS full_name,
                   u.active                 AS user_active,
                   m.active                 AS membership_active,
                   (m.company_id IS NULL)   AS organization_wide,
                   m.created_at             AS created_at
            FROM tms.membership m
            JOIN tms.app_user u ON u.id = m.app_user_id
            """;

    /** Rule 1 of the class comment. Every read of the people list starts from exactly this. */
    private static final String REACHES_THIS_COMPANY = """
            WHERE m.organization_id = :organizationId
              AND (m.company_id = :companyId OR m.company_id IS NULL)
            """;

    private static final String ROLES_OF_MEMBERSHIPS_SQL = """
            SELECT mr.membership_id AS membership_id, r.code AS role_code
            FROM tms.membership_role mr
            JOIN tms.role r ON r.id = mr.role_id
            WHERE mr.membership_id IN (:membershipIds)
            ORDER BY r.code
            """;

    /**
     * The role catalogue with what each role grants, in one statement. The permission codes are
     * aggregated in the database rather than fetched per role: there are four roles and roughly
     * fifty permissions, and a query per role to render a picker is the shape that becomes an N+1
     * the moment custom roles arrive.
     */
    private static final String ROLE_CATALOGUE_SQL = """
            SELECT r.id                                                    AS role_id,
                   r.code                                                  AS role_code,
                   r.name                                                  AS role_name,
                   r.description                                           AS role_description,
                   r.scope_level                                           AS scope_level,
                   COALESCE(string_agg(p.code, ',' ORDER BY p.code), '')    AS permission_codes
            FROM tms.role r
            LEFT JOIN tms.role_permission rp ON rp.role_id = r.id
            LEFT JOIN tms.permission p ON p.id = rp.permission_id
            WHERE r.active
            GROUP BY r.id, r.code, r.name, r.description, r.scope_level
            ORDER BY r.code
            """;

    private static final String ROLES_BY_CODE_SQL = """
            SELECT id AS role_id, code AS role_code, scope_level AS scope_level
            FROM tms.role
            WHERE code IN (:codes)
              AND active
            """;

    private static final String APP_USER_BY_EMAIL_SQL = """
            SELECT id, full_name, active
            FROM tms.app_user
            WHERE email = :email
            """;

    private static final String INSERT_APP_USER_SQL = """
            INSERT INTO tms.app_user (email, full_name, created_by, updated_by)
            VALUES (:email, :fullName, :actorId, :actorId)
            RETURNING id
            """;

    private static final String UPDATE_APP_USER_NAME_SQL = """
            UPDATE tms.app_user
               SET full_name = :fullName,
                   updated_by = :actorId
             WHERE id = :appUserId
            """;

    private static final String COMPANY_MEMBERSHIP_SQL = """
            SELECT id, active
            FROM tms.membership
            WHERE app_user_id = :appUserId
              AND organization_id = :organizationId
              AND company_id = :companyId
            """;

    private static final String INSERT_MEMBERSHIP_SQL = """
            INSERT INTO tms.membership (app_user_id, organization_id, company_id, created_by, updated_by)
            VALUES (:appUserId, :organizationId, :companyId, :actorId, :actorId)
            RETURNING id
            """;

    /** Rule 2 of the class comment: {@code company_id = :companyId} never matches a NULL. */
    private static final String SET_MEMBERSHIP_ACTIVE_SQL = """
            UPDATE tms.membership
               SET active = :active,
                   updated_by = :actorId
             WHERE id = :membershipId
               AND company_id = :companyId
            """;

    private static final String DELETE_MEMBERSHIP_ROLES_SQL = """
            DELETE FROM tms.membership_role
            WHERE membership_id = :membershipId
            """;

    private static final String INSERT_MEMBERSHIP_ROLE_SQL = """
            INSERT INTO tms.membership_role (membership_id, role_id, created_by)
            VALUES (:membershipId, :roleId, :actorId)
            """;

    private final JdbcClient jdbcClient;

    public UserAdministrationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * One page of the people who can act in this company.
     *
     * @param search matched case-insensitively against the email and the full name; already
     *     lower-cased and wrapped in {@code %} by the caller, or {@code null}
     * @param active {@code null} for "any", otherwise the membership's own active flag
     */
    public List<AdministeredUserRow> listUsers(UUID organizationId, UUID companyId, String search, Boolean active,
            PageQuery pageQuery) {
        StringBuilder sql = new StringBuilder(USER_COLUMNS).append(REACHES_THIS_COMPANY);
        appendFilters(sql, search, active);
        sql.append(orderBy(pageQuery)).append("\nLIMIT :limit OFFSET :offset");

        JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString())
                .param("organizationId", organizationId)
                .param("companyId", companyId)
                .param("limit", pageQuery.pageSize())
                .param("offset", pageQuery.offset());
        statement = bindFilters(statement, search, active);
        return statement.query(USER_MAPPER).list();
    }

    public long countUsers(UUID organizationId, UUID companyId, String search, Boolean active) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*)
                FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id
                """).append(REACHES_THIS_COMPANY);
        appendFilters(sql, search, active);

        JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString())
                .param("organizationId", organizationId)
                .param("companyId", companyId);
        statement = bindFilters(statement, search, active);
        Long total = statement.query(Long.class).single();
        return total == null ? 0L : total;
    }

    /** One membership of this company, or of the organization above it, by its own id. */
    public Optional<AdministeredUserRow> findUser(UUID organizationId, UUID companyId, UUID membershipId) {
        return jdbcClient.sql(USER_COLUMNS + REACHES_THIS_COMPANY + "  AND m.id = :membershipId\n")
                .param("organizationId", organizationId)
                .param("companyId", companyId)
                .param("membershipId", membershipId)
                .query(USER_MAPPER)
                .optional();
    }

    /**
     * The role codes held by each of the given memberships, batched - one statement for a whole
     * page, never one per row. Memberships with no roles are absent from the map rather than
     * present with an empty list; {@code UserAdministrationService} reads it with a default.
     */
    public Map<UUID, List<String>> roleCodesOf(Collection<UUID> membershipIds) {
        Map<UUID, List<String>> byMembership = new LinkedHashMap<>();
        if (membershipIds.isEmpty()) {
            return byMembership;
        }
        for (MembershipRoleRow row : jdbcClient.sql(ROLES_OF_MEMBERSHIPS_SQL)
                .param("membershipIds", membershipIds)
                .query(MEMBERSHIP_ROLE_MAPPER)
                .list()) {
            byMembership.computeIfAbsent(row.membershipId(), key -> new ArrayList<>()).add(row.roleCode());
        }
        return byMembership;
    }

    public List<String> roleCodesOf(UUID membershipId) {
        return roleCodesOf(List.of(membershipId)).getOrDefault(membershipId, List.of());
    }

    public List<RoleCatalogueRow> findRoleCatalogue() {
        return jdbcClient.sql(ROLE_CATALOGUE_SQL)
                .query((rs, rowNum) -> new RoleCatalogueRow(
                        rs.getObject("role_id", UUID.class),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("role_description"),
                        rs.getString("scope_level"),
                        splitCodes(rs.getString("permission_codes"))))
                .list();
    }

    /**
     * Resolves role codes to ids, dropping anything the catalogue does not hold. The caller
     * compares sizes and refuses the request rather than silently granting a subset - a role
     * assignment that quietly ignored a typo would be an administrator believing they granted
     * something they did not.
     */
    public Map<String, RoleReference> findRolesByCode(Set<String> codes) {
        Map<String, RoleReference> byCode = new LinkedHashMap<>();
        if (codes.isEmpty()) {
            return byCode;
        }
        jdbcClient.sql(ROLES_BY_CODE_SQL)
                .param("codes", codes)
                .query((rs, rowNum) -> new RoleReference(
                        rs.getObject("role_id", UUID.class),
                        rs.getString("role_code"),
                        rs.getString("scope_level")))
                .list()
                .forEach(role -> byCode.put(role.code(), role));
        return byCode;
    }

    public Optional<ExistingAppUser> findAppUserByEmail(String email) {
        return jdbcClient.sql(APP_USER_BY_EMAIL_SQL)
                .param("email", email)
                .query((rs, rowNum) -> new ExistingAppUser(
                        rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getBoolean("active")))
                .optional();
    }

    public UUID insertAppUser(String email, String fullName, UUID actorId) {
        return jdbcClient.sql(INSERT_APP_USER_SQL)
                .param("email", email)
                .param("fullName", fullName)
                .param("actorId", actorId)
                .query(UUID.class)
                .single();
    }

    public int updateAppUserName(UUID appUserId, String fullName, UUID actorId) {
        return jdbcClient.sql(UPDATE_APP_USER_NAME_SQL)
                .param("appUserId", appUserId)
                .param("fullName", fullName)
                .param("actorId", actorId)
                .update();
    }

    public Optional<ExistingMembership> findCompanyMembership(UUID appUserId, UUID organizationId, UUID companyId) {
        return jdbcClient.sql(COMPANY_MEMBERSHIP_SQL)
                .param("appUserId", appUserId)
                .param("organizationId", organizationId)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new ExistingMembership(
                        rs.getObject("id", UUID.class), rs.getBoolean("active")))
                .optional();
    }

    public UUID insertMembership(UUID appUserId, UUID organizationId, UUID companyId, UUID actorId) {
        return jdbcClient.sql(INSERT_MEMBERSHIP_SQL)
                .param("appUserId", appUserId)
                .param("organizationId", organizationId)
                .param("companyId", companyId)
                .param("actorId", actorId)
                .query(UUID.class)
                .single();
    }

    /** @return the number of rows changed - zero when the membership is organization-wide */
    public int setMembershipActive(UUID membershipId, UUID companyId, boolean active, UUID actorId) {
        return jdbcClient.sql(SET_MEMBERSHIP_ACTIVE_SQL)
                .param("membershipId", membershipId)
                .param("companyId", companyId)
                .param("active", active)
                .param("actorId", actorId)
                .update();
    }

    /**
     * Replaces the roles of one membership wholesale.
     *
     * <p>Delete-then-insert rather than a diff, on purpose: {@code tms.membership_role} is a pure
     * configuration link with a composite primary key and no history of its own (V2 says so), the
     * set is four rows at most, and the audit event this runs beside records the codes before and
     * after - which is the history a diff would have been protecting. Both statements are in the
     * caller's transaction, so a failure leaves the previous roles in place rather than a person
     * with none.
     */
    public void replaceMembershipRoles(UUID membershipId, Collection<UUID> roleIds, UUID actorId) {
        jdbcClient.sql(DELETE_MEMBERSHIP_ROLES_SQL).param("membershipId", membershipId).update();
        for (UUID roleId : roleIds) {
            jdbcClient.sql(INSERT_MEMBERSHIP_ROLE_SQL)
                    .param("membershipId", membershipId)
                    .param("roleId", roleId)
                    .param("actorId", actorId)
                    .update();
        }
    }

    private static void appendFilters(StringBuilder sql, String search, Boolean active) {
        if (search != null) {
            sql.append("  AND (u.email LIKE :search OR lower(u.full_name) LIKE :search)\n");
        }
        if (active != null) {
            sql.append("  AND m.active = :active\n");
        }
    }

    private static JdbcClient.StatementSpec bindFilters(
            JdbcClient.StatementSpec statement, String search, Boolean active) {
        JdbcClient.StatementSpec bound = statement;
        if (search != null) {
            bound = bound.param("search", search);
        }
        if (active != null) {
            bound = bound.param("active", active);
        }
        return bound;
    }

    /**
     * Newest membership last and the list alphabetical by name by default: an administrator opens
     * this screen to find a person, not to see who joined most recently.
     */
    private static String orderBy(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_COLUMNS.keySet());
        if (terms.isEmpty()) {
            return "ORDER BY u.full_name ASC, u.email ASC";
        }
        StringBuilder order = new StringBuilder("ORDER BY ");
        for (int index = 0; index < terms.size(); index++) {
            PageQuery.SortTerm term = terms.get(index);
            if (index > 0) {
                order.append(", ");
            }
            // SORTABLE_COLUMNS is the allow-list PageQuery already validated the property against,
            // so this lookup cannot miss and the text appended is never caller-supplied.
            order.append(SORTABLE_COLUMNS.get(term.property())).append(' ').append(term.direction());
        }
        return order.toString();
    }

    /** One row of {@link #ROLES_OF_MEMBERSHIPS_SQL}, before it is grouped by membership. */
    private record MembershipRoleRow(UUID membershipId, String roleCode) {}

    private static final RowMapper<MembershipRoleRow> MEMBERSHIP_ROLE_MAPPER = (rs, rowNum) -> new MembershipRoleRow(
            rs.getObject("membership_id", UUID.class), rs.getString("role_code"));

    private static final RowMapper<AdministeredUserRow> USER_MAPPER = (rs, rowNum) -> new AdministeredUserRow(
            rs.getObject("membership_id", UUID.class),
            rs.getObject("app_user_id", UUID.class),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getBoolean("user_active"),
            rs.getBoolean("membership_active"),
            rs.getBoolean("organization_wide"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static List<String> splitCodes(String aggregated) {
        if (aggregated == null || aggregated.isBlank()) {
            return List.of();
        }
        return Arrays.stream(aggregated.split(",")).map(String::trim).filter(code -> !code.isEmpty()).toList();
    }

    /** A role resolved by code, with the scope level that decides whether it may be granted here. */
    public record RoleReference(UUID id, String code, String scopeLevel) {

        public boolean assignableToCompanyMembership() {
            return RoleCatalogueRow.SCOPE_COMPANY.equals(scopeLevel);
        }
    }

    /** An existing {@code tms.app_user} found by email - the invite path's "this person is already here". */
    public record ExistingAppUser(UUID id, String fullName, boolean active) {}

    /** An existing company-scoped membership, active or revoked. */
    public record ExistingMembership(UUID id, boolean active) {}
}
