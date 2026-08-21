package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.iam.domain.CompanyProfileRow;
import com.ebim.tms.shared.settings.CompanySettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The company half of tenant administration: the company row, the organization above it and the
 * settings row beside it (migration V34).
 *
 * <p>Plain JDBC, like every other statement in this module and for the reason
 * {@link JdbcIdentityRepository} gives: identity and tenancy have no JPA entities anywhere in TMS,
 * and introducing them here would create a second model of rows that the authentication hot path
 * already reads in explicit SQL. Two models of {@code tms.company} is exactly how "which one is
 * right" becomes a question.
 *
 * <p>Every statement carries its company id or organization id as a predicate even though the
 * caller has already been scope-checked. That is not belt and braces for its own sake: an
 * {@code UPDATE} without it is one careless edit away from renaming somebody else's company, and
 * {@code tms.company} is one of the tables V13 section 5 deliberately leaves un-tenanted at the
 * database level, so there is no policy underneath to catch it.
 */
@Repository
public class CompanyAdministrationRepository {

    private static final String PROFILE_SQL = """
            SELECT c.id                     AS company_id,
                   c.code                   AS company_code,
                   c.name                   AS company_name,
                   c.tax_identifier         AS tax_identifier,
                   c.time_zone              AS time_zone,
                   c.active                 AS company_active,
                   o.id                     AS organization_id,
                   o.code                   AS organization_code,
                   o.name                   AS organization_name,
                   o.active                 AS organization_active,
                   s.default_country        AS default_country,
                   s.order_number_prefix    AS order_number_prefix,
                   s.shipment_number_prefix AS shipment_number_prefix
            FROM tms.company c
            JOIN tms.organization o ON o.id = c.organization_id
            LEFT JOIN tms.company_settings s ON s.company_id = c.id
            WHERE c.id = :companyId
            """;

    private static final String UPDATE_COMPANY_SQL = """
            UPDATE tms.company
               SET name = :name,
                   tax_identifier = :taxIdentifier,
                   time_zone = :timeZone,
                   updated_by = :actorId
             WHERE id = :companyId
            """;

    /**
     * Insert-or-update in one statement. The row may or may not exist (V34 section 4), and a
     * read-then-branch would be a race between two administrators saving the same screen: the loser
     * of that race would take a primary-key violation on a settings save.
     */
    private static final String UPSERT_SETTINGS_SQL = """
            INSERT INTO tms.company_settings (
                company_id, default_country,
                order_number_prefix, shipment_number_prefix, created_by, updated_by)
            VALUES (:companyId, :defaultCountry,
                :orderNumberPrefix, :shipmentNumberPrefix, :actorId, :actorId)
            ON CONFLICT (company_id) DO UPDATE
               SET default_country = EXCLUDED.default_country,
                   order_number_prefix = EXCLUDED.order_number_prefix,
                   shipment_number_prefix = EXCLUDED.shipment_number_prefix,
                   updated_by = EXCLUDED.updated_by
            """;

    private static final String INSERT_COMPANY_SQL = """
            INSERT INTO tms.company (organization_id, code, name, tax_identifier, time_zone, created_by, updated_by)
            VALUES (:organizationId, :code, :name, :taxIdentifier, :timeZone, :actorId, :actorId)
            RETURNING id
            """;

    private static final String COMPANY_CODE_TAKEN_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM tms.company
                 WHERE organization_id = :organizationId
                   AND code = :code)
            """;

    /**
     * Whether the caller holds an active organization-wide membership in this organization - a
     * {@code membership} row with {@code company_id IS NULL}.
     *
     * <p>The question exists because creating a company is the one administrative act that reaches
     * <em>outside</em> the company the request is scoped to. Every other endpoint in TMS is safe by
     * construction: {@code CompanyScope} was resolved from the caller's own memberships, so a
     * company id can never be chosen by the caller. A new company has no membership yet and
     * therefore no scope to check, so the authority has to come from the level above it.
     *
     * <p>{@code iam.company:manage} alone is not enough for it, and that is deliberate: a
     * COMPANY_ADMIN holds that permission and is, by the definition V3 gives the role, an
     * administrator of <em>one</em> company. Requiring the organization-wide membership is what
     * makes "create a company" an ORGANIZATION_ADMIN act without minting a permission that every
     * existing installation would have to grant before its administration screen worked.
     */
    private static final String ORGANIZATION_WIDE_MEMBERSHIP_SQL = """
            SELECT EXISTS (
                SELECT 1
                  FROM tms.membership m
                  JOIN tms.organization o ON o.id = m.organization_id AND o.active
                 WHERE m.app_user_id = :appUserId
                   AND m.organization_id = :organizationId
                   AND m.company_id IS NULL
                   AND m.active)
            """;

    private final JdbcClient jdbcClient;

    public CompanyAdministrationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CompanyProfileRow> findProfile(UUID companyId) {
        return jdbcClient.sql(PROFILE_SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new CompanyProfileRow(
                        rs.getObject("company_id", UUID.class),
                        rs.getString("company_code"),
                        rs.getString("company_name"),
                        rs.getString("tax_identifier"),
                        rs.getString("time_zone"),
                        rs.getBoolean("company_active"),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("organization_code"),
                        rs.getString("organization_name"),
                        rs.getBoolean("organization_active"),
                        // The LEFT JOIN produces nulls for a company with no settings row; the
                        // record's canonical constructor turns those into the documented defaults,
                        // so there is no second "was it null" branch here or in any caller.
                        new CompanySettings(
                                rs.getString("default_country"),
                                rs.getString("order_number_prefix"),
                                rs.getString("shipment_number_prefix"))))
                .optional();
    }

    public int updateCompany(UUID companyId, String name, String taxIdentifier, String timeZone, UUID actorId) {
        return jdbcClient.sql(UPDATE_COMPANY_SQL)
                .param("companyId", companyId)
                .param("name", name)
                .param("taxIdentifier", taxIdentifier)
                .param("timeZone", timeZone)
                .param("actorId", actorId)
                .update();
    }

    public void saveSettings(UUID companyId, CompanySettings settings, UUID actorId) {
        jdbcClient.sql(UPSERT_SETTINGS_SQL)
                .param("companyId", companyId)
                .param("defaultCountry", settings.defaultCountry())
                .param("orderNumberPrefix", settings.orderNumberPrefix())
                .param("shipmentNumberPrefix", settings.shipmentNumberPrefix())
                .param("actorId", actorId)
                .update();
    }

    public boolean companyCodeTaken(UUID organizationId, String code) {
        return Boolean.TRUE.equals(jdbcClient.sql(COMPANY_CODE_TAKEN_SQL)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(Boolean.class)
                .single());
    }

    public UUID insertCompany(UUID organizationId, String code, String name, String taxIdentifier,
            String timeZone, UUID actorId) {
        return jdbcClient.sql(INSERT_COMPANY_SQL)
                .param("organizationId", organizationId)
                .param("code", code)
                .param("name", name)
                .param("taxIdentifier", taxIdentifier)
                .param("timeZone", timeZone)
                .param("actorId", actorId)
                .query(UUID.class)
                .single();
    }

    /** See {@link #ORGANIZATION_WIDE_MEMBERSHIP_SQL} for why this question is asked at all. */
    public boolean hasOrganizationWideMembership(UUID appUserId, UUID organizationId) {
        return Boolean.TRUE.equals(jdbcClient.sql(ORGANIZATION_WIDE_MEMBERSHIP_SQL)
                .param("appUserId", appUserId)
                .param("organizationId", organizationId)
                .query(Boolean.class)
                .single());
    }
}
