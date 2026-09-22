package com.ebim.tms.iam.provisioning.infrastructure;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement the provisioning surface runs.
 *
 * <p>Runs on the backend's owning connection: the caller is a {@code PlatformProvisioningAuthentication},
 * never a company-scoped one, so {@code TenantScopedDataSource} hands the connection over untouched.
 * That is required, not incidental - the identity tables and the V51 tables are not writable by
 * {@code tms_app}.
 *
 * <p>There is deliberately no statement here that writes {@code app_user.auth_user_id} or touches the
 * Supabase {@code auth} schema: provisioning never activates a login.
 */
@Repository
public class PlatformProvisioningRepository {

    /**
     * Serializes concurrent calls for the same key or the same MasterAdmin tenant until the end of
     * the transaction. A function in FROM rather than in the select list because it returns void.
     */
    private static final String LOCK_SQL = """
            SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockName, 0))
            """;

    private static final String RECORD_SQL = """
            SELECT r.id                      AS provisioning_id,
                   r.control_plane_tenant_id AS control_plane_tenant_id,
                   r.request_hash            AS request_hash,
                   o.id                      AS organization_id,
                   o.code                    AS organization_code,
                   o.active                  AS organization_active,
                   c.id                      AS company_id,
                   c.code                    AS company_code,
                   c.time_zone               AS company_time_zone,
                   c.active                  AS company_active,
                   r.admin_profile_reused    AS admin_profile_reused,
                   (u.auth_user_id IS NOT NULL) AS admin_auth_linked,
                   r.created_at              AS created_at
            FROM tms.platform_provisioning_request r
            JOIN tms.organization o ON o.id = r.organization_id
            JOIN tms.company c ON c.id = r.company_id
            JOIN tms.app_user u ON u.id = r.admin_app_user_id
            """;

    private static final String BY_IDEMPOTENCY_KEY_SQL = RECORD_SQL + " WHERE r.idempotency_key = :key";

    private static final String BY_CONTROL_PLANE_TENANT_SQL = RECORD_SQL + " WHERE r.control_plane_tenant_id = :cpt";

    private static final String ORGANIZATION_CODE_TAKEN_SQL = """
            SELECT EXISTS (SELECT 1 FROM tms.organization WHERE code = :code)
            """;

    private static final String PROFILE_BY_EMAIL_SQL = """
            SELECT id, active FROM tms.app_user WHERE email = :email
            """;

    private static final String INSERT_ORGANIZATION_SQL = """
            INSERT INTO tms.organization (code, name)
            VALUES (:code, :name)
            RETURNING id
            """;

    private static final String INSERT_COMPANY_SQL = """
            INSERT INTO tms.company (organization_id, code, name, tax_identifier, time_zone)
            VALUES (:organizationId, :code, :name, :taxIdentifier, :timeZone)
            RETURNING id
            """;

    private static final String INSERT_COMPANY_SETTINGS_SQL = """
            INSERT INTO tms.company_settings (company_id, default_country)
            VALUES (:companyId, :country)
            """;

    private static final String INSERT_APP_USER_SQL = """
            INSERT INTO tms.app_user (email, full_name)
            VALUES (:email, :fullName)
            RETURNING id
            """;

    private static final String INSERT_ORGANIZATION_MEMBERSHIP_SQL = """
            INSERT INTO tms.membership (app_user_id, organization_id, company_id)
            VALUES (:appUserId, :organizationId, NULL)
            RETURNING id
            """;

    /** Zero rows means the role catalogue is broken, and the caller treats it as a failure. */
    private static final String GRANT_ROLE_SQL = """
            INSERT INTO tms.membership_role (membership_id, role_id)
            SELECT :membershipId, r.id
            FROM tms.role r
            WHERE r.code = :roleCode AND r.active
            """;

    private static final String INSERT_PROVISIONING_SQL = """
            INSERT INTO tms.platform_provisioning_request (
                idempotency_key, request_hash, control_plane_tenant_id, organization_id, company_id,
                admin_app_user_id, admin_membership_id, admin_profile_reused, product_code,
                contract_version, tenant_code, environment, tenant_type, deployment_mode, plan_code,
                correlation_id, m2m_subject, m2m_jti, actor_id, actor_role)
            VALUES (
                :idempotencyKey, :requestHash, :cpt, :organizationId, :companyId,
                :adminAppUserId, :adminMembershipId, :adminProfileReused, :productCode,
                :contractVersion, :tenantCode, :environment, :tenantType, :deploymentMode, :planCode,
                :correlationId, :m2mSubject, :m2mJti, :actorId, :actorRole)
            RETURNING id
            """;

    private static final String INSERT_AUDIT_SQL = """
            INSERT INTO tms.platform_provisioning_audit (
                operation, result, http_status, error_code, control_plane_tenant_id, idempotency_key,
                provisioning_id, correlation_id, m2m_subject, m2m_jti, actor_id, actor_role)
            VALUES (
                :operation, :result, :httpStatus, :errorCode, :cpt, :idempotencyKey,
                :provisioningId, :correlationId, :m2mSubject, :m2mJti, :actorId, :actorRole)
            """;

    private final JdbcClient jdbcClient;

    public PlatformProvisioningRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lock(String lockName) {
        jdbcClient.sql(LOCK_SQL).param("lockName", "tms.platform-provisioning:" + lockName)
                .query(Integer.class).single();
    }

    public Optional<ProvisioningRecord> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql(BY_IDEMPOTENCY_KEY_SQL).param("key", idempotencyKey)
                .query(PlatformProvisioningRepository::record).optional();
    }

    public Optional<ProvisioningRecord> findByControlPlaneTenantId(UUID controlPlaneTenantId) {
        return jdbcClient.sql(BY_CONTROL_PLANE_TENANT_SQL).param("cpt", controlPlaneTenantId)
                .query(PlatformProvisioningRepository::record).optional();
    }

    public boolean organizationCodeTaken(String code) {
        return Boolean.TRUE.equals(jdbcClient.sql(ORGANIZATION_CODE_TAKEN_SQL).param("code", code)
                .query(Boolean.class).single());
    }

    public Optional<ExistingProfile> findProfileByEmail(String email) {
        return jdbcClient.sql(PROFILE_BY_EMAIL_SQL).param("email", email)
                .query((rs, row) -> new ExistingProfile(rs.getObject("id", UUID.class), rs.getBoolean("active")))
                .optional();
    }

    public UUID insertOrganization(String code, String name) {
        return jdbcClient.sql(INSERT_ORGANIZATION_SQL).param("code", code).param("name", name)
                .query(UUID.class).single();
    }

    public UUID insertCompany(UUID organizationId, String code, String name, String taxIdentifier, String timeZone) {
        return jdbcClient.sql(INSERT_COMPANY_SQL)
                .param("organizationId", organizationId)
                .param("code", code)
                .param("name", name)
                .param("taxIdentifier", taxIdentifier)
                .param("timeZone", timeZone)
                .query(UUID.class).single();
    }

    public void insertCompanySettings(UUID companyId, String defaultCountry) {
        jdbcClient.sql(INSERT_COMPANY_SETTINGS_SQL).param("companyId", companyId).param("country", defaultCountry)
                .update();
    }

    /** A profile with {@code auth_user_id} NULL: the person cannot sign in until an operator links it. */
    public UUID insertPreprovisionedProfile(String email, String fullName) {
        return jdbcClient.sql(INSERT_APP_USER_SQL).param("email", email).param("fullName", fullName)
                .query(UUID.class).single();
    }

    public UUID insertOrganizationWideMembership(UUID appUserId, UUID organizationId) {
        return jdbcClient.sql(INSERT_ORGANIZATION_MEMBERSHIP_SQL)
                .param("appUserId", appUserId)
                .param("organizationId", organizationId)
                .query(UUID.class).single();
    }

    public int grantRole(UUID membershipId, String roleCode) {
        return jdbcClient.sql(GRANT_ROLE_SQL).param("membershipId", membershipId).param("roleCode", roleCode)
                .update();
    }

    public UUID insertProvisioning(NewProvisioning row, ProvisioningMetadata metadata) {
        return jdbcClient.sql(INSERT_PROVISIONING_SQL)
                .param("idempotencyKey", metadata.idempotencyKey())
                .param("requestHash", row.requestHash())
                .param("cpt", row.controlPlaneTenantId())
                .param("organizationId", row.organizationId())
                .param("companyId", row.companyId())
                .param("adminAppUserId", row.adminAppUserId())
                .param("adminMembershipId", row.adminMembershipId())
                .param("adminProfileReused", row.adminProfileReused())
                .param("productCode", row.productCode())
                .param("contractVersion", row.contractVersion())
                .param("tenantCode", row.tenantCode())
                .param("environment", row.environment())
                .param("tenantType", row.tenantType())
                .param("deploymentMode", row.deploymentMode())
                .param("planCode", row.planCode())
                .param("correlationId", metadata.correlationId())
                .param("m2mSubject", metadata.m2mSubject())
                .param("m2mJti", metadata.m2mJti())
                .param("actorId", metadata.actorId())
                .param("actorRole", metadata.actorRole())
                .query(UUID.class).single();
    }

    public void recordAudit(ProvisioningAuditEntry entry) {
        jdbcClient.sql(INSERT_AUDIT_SQL)
                .param("operation", entry.operation().name())
                .param("result", entry.result().name())
                .param("httpStatus", entry.httpStatus())
                .param("errorCode", entry.errorCode())
                .param("cpt", entry.controlPlaneTenantId())
                .param("idempotencyKey", entry.idempotencyKey())
                .param("provisioningId", entry.provisioningId())
                .param("correlationId", entry.correlationId())
                .param("m2mSubject", entry.m2mSubject())
                .param("m2mJti", entry.m2mJti())
                .param("actorId", entry.actorId())
                .param("actorRole", entry.actorRole())
                .update();
    }

    private static ProvisioningRecord record(ResultSet rs, int row) throws SQLException {
        return new ProvisioningRecord(
                rs.getObject("provisioning_id", UUID.class),
                rs.getObject("control_plane_tenant_id", UUID.class),
                rs.getString("request_hash"),
                rs.getObject("organization_id", UUID.class),
                rs.getString("organization_code"),
                rs.getBoolean("organization_active"),
                rs.getObject("company_id", UUID.class),
                rs.getString("company_code"),
                rs.getString("company_time_zone"),
                rs.getBoolean("company_active"),
                rs.getBoolean("admin_profile_reused"),
                rs.getBoolean("admin_auth_linked"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /** An existing {@code tms.app_user} with the requested email. */
    public record ExistingProfile(UUID id, boolean active) {}

    /** The functional columns of a new {@code platform_provisioning_request} row. */
    public record NewProvisioning(
            UUID controlPlaneTenantId,
            String requestHash,
            UUID organizationId,
            UUID companyId,
            UUID adminAppUserId,
            UUID adminMembershipId,
            boolean adminProfileReused,
            String productCode,
            String contractVersion,
            String tenantCode,
            String environment,
            String tenantType,
            String deploymentMode,
            String planCode) {}
}
