package com.ebim.tms.iam.entitlements.infrastructure;

import com.ebim.tms.iam.entitlements.application.EntitlementStore;
import com.ebim.tms.iam.entitlements.domain.AppliedStatus;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every statement of the entitlements receiver (V52).
 *
 * <p>Runs on the owning connection, like {@code PlatformProvisioningRepository}: the M2M caller is
 * never company-scoped, so {@code TenantScopedDataSource} leaves the connection alone. The one
 * exception is {@link #currentCompanyAccess()}, which a company-scoped request calls as
 * {@code tms_app} through the SECURITY DEFINER function V52 provides for exactly that.
 *
 * <p>V51's {@code platform_provisioning_request} is READ here to resolve the tenant, never written
 * or locked (INV-1).
 */
@Repository
public class JdbcEntitlementRepository implements EntitlementStore {

    private static final String PROVISIONED_SQL = """
            SELECT control_plane_tenant_id, organization_id
            FROM tms.platform_provisioning_request
            WHERE control_plane_tenant_id = :cpt AND status = 'ACTIVE'
            """;

    private static final String PROVISIONED_ORGANIZATION_SQL = """
            SELECT control_plane_tenant_id, organization_id
            FROM tms.platform_provisioning_request
            WHERE organization_id = :organizationId AND status = 'ACTIVE'
            """;

    /** Expired jti rows are useless: a token carrying them would be refused for its age anyway. */
    private static final String PURGE_JTI_SQL = """
            DELETE FROM tms.platform_entitlement_jti WHERE expires_at < now() - interval '1 hour'
            """;

    private static final String CONSUME_JTI_SQL = """
            INSERT INTO tms.platform_entitlement_jti (issuer, jti, expires_at)
            VALUES (:issuer, :jti, :expiresAt)
            ON CONFLICT (issuer, jti) DO NOTHING
            """;

    /** Its own lock namespace: provisioning locks on {@code tms.platform-provisioning:*}. */
    private static final String LOCK_SQL = """
            SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockName, 0))
            """;

    private static final String APPLIED_SQL = """
            SELECT control_plane_tenant_id, organization_id, product_code, snapshot::text AS snapshot,
                   snapshot_version, checksum, status, app_active,
                   to_jsonb(unknown_capabilities)::text AS unknown_capabilities, applied_at
            FROM tms.platform_entitlement_applied
            WHERE control_plane_tenant_id = :cpt
            """;

    private static final String SAVE_SQL = """
            INSERT INTO tms.platform_entitlement_applied (
                control_plane_tenant_id, organization_id, product_code, snapshot, snapshot_version, checksum,
                status, app_active, unknown_capabilities, applied_at, correlation_id, m2m_subject, m2m_jti)
            VALUES (
                :cpt, :organizationId, :productCode, CAST(:snapshot AS jsonb), :version, :checksum,
                :status, :appActive, ARRAY(SELECT jsonb_array_elements_text(CAST(:unknown AS jsonb))),
                :appliedAt, :correlationId, :subject, :jti)
            ON CONFLICT (control_plane_tenant_id) DO UPDATE SET
                snapshot = EXCLUDED.snapshot, snapshot_version = EXCLUDED.snapshot_version,
                checksum = EXCLUDED.checksum, status = EXCLUDED.status, app_active = EXCLUDED.app_active,
                unknown_capabilities = EXCLUDED.unknown_capabilities, applied_at = EXCLUDED.applied_at,
                correlation_id = EXCLUDED.correlation_id, m2m_subject = EXCLUDED.m2m_subject,
                m2m_jti = EXCLUDED.m2m_jti
            """;

    private static final String AUDIT_SQL = """
            INSERT INTO tms.platform_entitlement_audit (
                control_plane_tenant_id, snapshot_version, checksum, outcome, correlation_id, m2m_subject,
                m2m_jti, detail)
            VALUES (:cpt, :version, :checksum, :outcome, :correlationId, :subject, :jti, CAST(:detail AS jsonb))
            """;

    private static final String MODE_SQL = """
            SELECT COALESCE(
                (SELECT mode FROM tms.platform_entitlement_mode WHERE scope_key = :tenantScope),
                (SELECT mode FROM tms.platform_entitlement_mode WHERE scope_key = 'PRODUCT'),
                'LEGACY')
            """;

    private static final String SHADOW_DIFF_SQL = """
            INSERT INTO tms.platform_entitlement_shadow_diff (
                control_plane_tenant_id, organization_id, kind, mode, legacy_decision, snapshot_decision,
                snapshot_version)
            VALUES (:cpt, :organizationId, :kind, :mode, :legacyDecision, :snapshotDecision, :version)
            """;

    private static final String CURRENT_COMPANY_ACCESS_SQL = """
            SELECT under_control_plane, enforcement_mode, app_active
            FROM tms.commercial_access_current_company()
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper json;

    public JdbcEntitlementRepository(JdbcClient jdbcClient, ObjectMapper json) {
        this.jdbcClient = jdbcClient;
        this.json = json;
    }

    @Override
    public Optional<ProvisionedTenant> provisioned(UUID controlPlaneTenantId) {
        return jdbcClient.sql(PROVISIONED_SQL).param("cpt", controlPlaneTenantId)
                .query(JdbcEntitlementRepository::tenant).optional();
    }

    @Override
    public Optional<ProvisionedTenant> provisionedOrganization(UUID organizationId) {
        return jdbcClient.sql(PROVISIONED_ORGANIZATION_SQL).param("organizationId", organizationId)
                .query(JdbcEntitlementRepository::tenant).optional();
    }

    @Override
    public boolean consumeJti(String issuer, String jti, Instant expiresAt) {
        jdbcClient.sql(PURGE_JTI_SQL).update();
        return jdbcClient.sql(CONSUME_JTI_SQL)
                .param("issuer", issuer == null ? "" : issuer)
                .param("jti", jti)
                .param("expiresAt", Timestamp.from(expiresAt))
                .update() == 1;
    }

    @Override
    public void lock(UUID controlPlaneTenantId) {
        jdbcClient.sql(LOCK_SQL).param("lockName", "tms.platform-entitlements:" + controlPlaneTenantId)
                .query(Integer.class).single();
    }

    @Override
    public Optional<Applied> applied(UUID controlPlaneTenantId) {
        return jdbcClient.sql(APPLIED_SQL).param("cpt", controlPlaneTenantId)
                .query((rs, row) -> new Applied(
                        rs.getObject("control_plane_tenant_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("product_code"),
                        rs.getLong("snapshot_version"),
                        rs.getString("checksum"),
                        AppliedStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("app_active"),
                        List.copyOf(json.readValue(rs.getString("unknown_capabilities"),
                                new TypeReference<List<String>>() { })),
                        rs.getTimestamp("applied_at").toInstant(),
                        (ObjectNode) json.readTree(rs.getString("snapshot"))))
                .optional();
    }

    @Override
    public void save(Applied applied, CallContext caller) {
        jdbcClient.sql(SAVE_SQL)
                .param("cpt", applied.controlPlaneTenantId())
                .param("organizationId", applied.organizationId())
                .param("productCode", applied.productCode())
                .param("snapshot", applied.snapshot().toString())
                .param("version", applied.version())
                .param("checksum", applied.checksum())
                .param("status", applied.status().name())
                .param("appActive", applied.appActive())
                .param("unknown", json.writeValueAsString(applied.unknownCapabilities()))
                .param("appliedAt", Timestamp.from(applied.appliedAt()))
                .param("correlationId", caller.correlationId())
                .param("subject", caller.subject())
                .param("jti", caller.jti())
                .update();
    }

    @Override
    public void audit(UUID controlPlaneTenantId, Long version, String checksum, String outcome, CallContext caller,
            Map<String, Object> detail) {
        jdbcClient.sql(AUDIT_SQL)
                .param("cpt", controlPlaneTenantId)
                .param("version", version)
                .param("checksum", checksum)
                .param("outcome", outcome)
                .param("correlationId", caller.correlationId())
                .param("subject", caller.subject())
                .param("jti", caller.jti())
                .param("detail", json.writeValueAsString(detail))
                .update();
    }

    @Override
    public EnforcementMode mode(UUID controlPlaneTenantId) {
        return EnforcementMode.valueOf(jdbcClient.sql(MODE_SQL)
                .param("tenantScope", controlPlaneTenantId == null ? PRODUCT_SCOPE : controlPlaneTenantId.toString())
                .query(String.class).single());
    }

    @Override
    public void shadowDiff(ShadowDiff diff) {
        jdbcClient.sql(SHADOW_DIFF_SQL)
                .param("cpt", diff.controlPlaneTenantId())
                .param("organizationId", diff.organizationId())
                .param("kind", diff.kind())
                .param("mode", diff.mode().name())
                .param("legacyDecision", diff.legacyDecision())
                .param("snapshotDecision", diff.snapshotDecision())
                .param("version", diff.snapshotVersion())
                .update();
    }

    @Override
    public Optional<AccessFacts> currentCompanyAccess() {
        return jdbcClient.sql(CURRENT_COMPANY_ACCESS_SQL)
                .query((rs, row) -> new AccessFacts(
                        rs.getBoolean("under_control_plane"),
                        EnforcementMode.valueOf(rs.getString("enforcement_mode")),
                        (Boolean) rs.getObject("app_active")))
                .optional();
    }

    private static ProvisionedTenant tenant(ResultSet rs, int row) throws SQLException {
        return new ProvisionedTenant(rs.getObject("control_plane_tenant_id", UUID.class),
                rs.getObject("organization_id", UUID.class));
    }
}
