package com.ebim.tms.iam.inventory.infrastructure;

import com.ebim.tms.iam.inventory.application.TenantInventoryStore;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement of {@code tenant-inventory.v1} (V76).
 *
 * <p>Runs on the backend's owning connection, like {@code PlatformProvisioningRepository}: the MasterAdmin
 * caller is never company-scoped, so {@code TenantScopedDataSource} hands the connection over untouched -
 * and must, because {@code tms_app} can read neither V51 nor V58. The list is deliberately cross-tenant:
 * it is the control plane's view of every organization, identity and state only.
 *
 * <p>The only write is a {@code LINKED} row of V51's {@code platform_provisioning_request}; the audit goes
 * through {@link PlatformProvisioningRepository#recordAudit}, the one statement for that table.
 */
@Repository
public class JdbcTenantInventoryRepository implements TenantInventoryStore {

    /**
     * One organization with what the inventory reports about it. The company is that of the provisioning
     * row when there is one (even NULL, for a link of a company-less organization), otherwise the oldest
     * company - which is what a link would record, so it does not change when the tenant is linked.
     */
    private static final String TENANT_SELECT = """
            SELECT o.id                         AS organization_id,
                   o.code                       AS code,
                   o.name                       AS name,
                   o.active                     AS active,
                   CASE WHEN r.id IS NOT NULL THEN r.company_id ELSE oldest.id END AS company_id,
                   r.control_plane_tenant_id    AS control_plane_tenant_id,
                   COALESCE(l.state = 'SUSPENDED', false) AS suspended
            FROM tms.organization o
            LEFT JOIN tms.platform_provisioning_request r
                   ON r.organization_id = o.id AND r.status = 'ACTIVE'
            LEFT JOIN tms.platform_tenant_lifecycle l ON l.organization_id = o.id
            LEFT JOIN LATERAL (
                SELECT c.id FROM tms.company c
                WHERE c.organization_id = o.id
                ORDER BY c.created_at, c.id
                LIMIT 1) oldest ON true
            """;

    private static final String FIRST_PAGE_SQL = TENANT_SELECT + " ORDER BY o.id LIMIT :limit";

    private static final String NEXT_PAGE_SQL = TENANT_SELECT + " WHERE o.id > :after ORDER BY o.id LIMIT :limit";

    private static final String TENANT_SQL = TENANT_SELECT + " WHERE o.id = :organizationId";

    private static final String MAPPING_SELECT = """
            SELECT id, control_plane_tenant_id, organization_id
            FROM tms.platform_provisioning_request
            """;

    private static final String MAPPING_OF_TENANT_SQL = MAPPING_SELECT + " WHERE control_plane_tenant_id = :cpt";

    private static final String MAPPING_OF_ORGANIZATION_SQL = MAPPING_SELECT + " WHERE organization_id = :organizationId";

    /** The lock namespace of {@code PlatformProvisioningRepository#lock}: a link and a create of one tenant serialize. */
    private static final String LOCK_SQL = """
            SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockName, 0))
            """;

    /** No administrator columns: V76's check requires them NULL for a LINKED row. */
    private static final String INSERT_LINK_SQL = """
            INSERT INTO tms.platform_provisioning_request (
                idempotency_key, request_hash, control_plane_tenant_id, organization_id, company_id,
                product_code, contract_version, tenant_code, environment,
                correlation_id, m2m_subject, m2m_jti, actor_id, actor_role, origin)
            VALUES (
                :idempotencyKey, :requestHash, :cpt, :organizationId, :companyId,
                :productCode, :contractVersion, :tenantCode, :environment,
                :correlationId, :m2mSubject, :m2mJti, :actorId, :actorRole, 'LINKED')
            RETURNING id
            """;

    private final JdbcClient jdbcClient;
    private final PlatformProvisioningRepository provisioning;

    public JdbcTenantInventoryRepository(JdbcClient jdbcClient, PlatformProvisioningRepository provisioning) {
        this.jdbcClient = jdbcClient;
        this.provisioning = provisioning;
    }

    @Override
    public List<Tenant> page(UUID after, int limit) {
        return after == null
                ? jdbcClient.sql(FIRST_PAGE_SQL).param("limit", limit).query(JdbcTenantInventoryRepository::tenant).list()
                : jdbcClient.sql(NEXT_PAGE_SQL).param("after", after).param("limit", limit)
                        .query(JdbcTenantInventoryRepository::tenant).list();
    }

    @Override
    public Optional<Tenant> tenant(UUID organizationId) {
        return jdbcClient.sql(TENANT_SQL).param("organizationId", organizationId)
                .query(JdbcTenantInventoryRepository::tenant).optional();
    }

    @Override
    public void lock(String name) {
        jdbcClient.sql(LOCK_SQL).param("lockName", "tms.platform-provisioning:" + name).query(Integer.class).single();
    }

    @Override
    public Optional<Mapping> mappingOfControlPlaneTenant(UUID controlPlaneTenantId) {
        return jdbcClient.sql(MAPPING_OF_TENANT_SQL).param("cpt", controlPlaneTenantId)
                .query(JdbcTenantInventoryRepository::mapping).optional();
    }

    @Override
    public Optional<Mapping> mappingOfOrganization(UUID organizationId) {
        return jdbcClient.sql(MAPPING_OF_ORGANIZATION_SQL).param("organizationId", organizationId)
                .query(JdbcTenantInventoryRepository::mapping).optional();
    }

    @Override
    public UUID insertLink(NewLink link, ProvisioningMetadata metadata) {
        return jdbcClient.sql(INSERT_LINK_SQL)
                // One key per MasterAdmin tenant: V51 requires a unique key, and a link has no
                // Idempotency-Key header - its idempotency is the (tenant, organization) pair.
                .param("idempotencyKey", "link:" + link.controlPlaneTenantId())
                .param("requestHash", link.requestHash())
                .param("cpt", link.controlPlaneTenantId())
                .param("organizationId", link.organizationId())
                .param("companyId", link.companyId())
                .param("productCode", link.productCode())
                .param("contractVersion", link.contractVersion())
                .param("tenantCode", link.tenantCode())
                .param("environment", link.environment())
                .param("correlationId", metadata.correlationId())
                .param("m2mSubject", metadata.m2mSubject())
                .param("m2mJti", metadata.m2mJti())
                .param("actorId", metadata.actorId())
                .param("actorRole", metadata.actorRole())
                .query(UUID.class).single();
    }

    @Override
    public void audit(ProvisioningAuditEntry entry) {
        provisioning.recordAudit(entry);
    }

    private static Tenant tenant(ResultSet rs, int row) throws SQLException {
        return new Tenant(
                rs.getObject("organization_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("active"),
                rs.getObject("company_id", UUID.class),
                rs.getObject("control_plane_tenant_id", UUID.class),
                rs.getBoolean("suspended"));
    }

    private static Mapping mapping(ResultSet rs, int row) throws SQLException {
        return new Mapping(rs.getObject("id", UUID.class), rs.getObject("control_plane_tenant_id", UUID.class),
                rs.getObject("organization_id", UUID.class));
    }
}
