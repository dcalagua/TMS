package com.ebim.tms.iam.lifecycle.infrastructure;

import com.ebim.tms.iam.lifecycle.application.LifecycleStore;
import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement of the lifecycle receiver (V58).
 *
 * <p>The PUT/GET run on the owning connection, like {@code JdbcEntitlementRepository}: the M2M caller is
 * never company-scoped, so {@code TenantScopedDataSource} leaves the connection alone. The two reads a
 * tenant request needs go through V58's SECURITY DEFINER functions, so they work on {@code tms_app}.
 *
 * <p>V51's {@code platform_provisioning_request} is READ here to resolve the tenant, never written.
 */
@Repository
public class JdbcLifecycleRepository implements LifecycleStore {

    private static final String PROVISIONED_SQL = """
            SELECT control_plane_tenant_id, organization_id
            FROM tms.platform_provisioning_request
            WHERE control_plane_tenant_id = :cpt AND status = 'ACTIVE'
            """;

    /** Its own lock namespace: provisioning and entitlements lock on theirs. */
    private static final String LOCK_SQL = """
            SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockName, 0))
            """;

    private static final String APPLIED_SQL = """
            SELECT control_plane_tenant_id, organization_id, state, reason, applied_version, applied_at,
                   effective_at, idempotency_key
            FROM tms.platform_tenant_lifecycle
            WHERE control_plane_tenant_id = :cpt
            """;

    private static final String SAVE_SQL = """
            INSERT INTO tms.platform_tenant_lifecycle (
                control_plane_tenant_id, organization_id, product_code, state, reason, applied_version,
                applied_at, effective_at, idempotency_key, correlation_id, m2m_subject, m2m_jti)
            VALUES (
                :cpt, :organizationId, 'tms', :state, :reason, :version,
                :appliedAt, :effectiveAt, :idempotencyKey, :correlationId, :subject, :jti)
            ON CONFLICT (control_plane_tenant_id) DO UPDATE SET
                state = EXCLUDED.state, reason = EXCLUDED.reason, applied_version = EXCLUDED.applied_version,
                applied_at = EXCLUDED.applied_at, effective_at = EXCLUDED.effective_at,
                idempotency_key = EXCLUDED.idempotency_key, correlation_id = EXCLUDED.correlation_id,
                m2m_subject = EXCLUDED.m2m_subject, m2m_jti = EXCLUDED.m2m_jti
            """;

    private static final String AUDIT_SQL = """
            INSERT INTO tms.platform_tenant_lifecycle_audit (
                control_plane_tenant_id, requested_version, requested_state, requested_reason, outcome,
                applied_version, idempotency_key, correlation_id, m2m_subject, m2m_jti)
            VALUES (:cpt, :version, :state, :reason, :outcome, :appliedVersion, :idempotencyKey,
                    :correlationId, :subject, :jti)
            """;

    private static final String CURRENT_COMPANY_SQL = """
            SELECT state, reason, applied_at FROM tms.tenant_lifecycle_current_company()
            """;

    private static final String ORGANIZATIONS_SQL = """
            SELECT organization_id, state, reason, applied_at
            FROM tms.tenant_lifecycle_of_organizations(CAST(:organizationIds AS uuid[]))
            """;

    private final JdbcClient jdbcClient;

    public JdbcLifecycleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ProvisionedTenant> provisioned(UUID controlPlaneTenantId) {
        return jdbcClient.sql(PROVISIONED_SQL).param("cpt", controlPlaneTenantId)
                .query((rs, row) -> new ProvisionedTenant(rs.getObject("control_plane_tenant_id", UUID.class),
                        rs.getObject("organization_id", UUID.class)))
                .optional();
    }

    @Override
    public void lock(UUID controlPlaneTenantId) {
        jdbcClient.sql(LOCK_SQL).param("lockName", "tms.platform-lifecycle:" + controlPlaneTenantId)
                .query(Integer.class).single();
    }

    @Override
    public Optional<Applied> applied(UUID controlPlaneTenantId) {
        return jdbcClient.sql(APPLIED_SQL).param("cpt", controlPlaneTenantId)
                .query((rs, row) -> new Applied(
                        rs.getObject("control_plane_tenant_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        LifecycleState.valueOf(rs.getString("state")),
                        reason(rs),
                        rs.getLong("applied_version"),
                        rs.getTimestamp("applied_at").toInstant(),
                        rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getString("idempotency_key")))
                .optional();
    }

    @Override
    public void save(Applied applied, CallContext caller) {
        jdbcClient.sql(SAVE_SQL)
                .param("cpt", applied.controlPlaneTenantId())
                .param("organizationId", applied.organizationId())
                .param("state", applied.state().name())
                .param("reason", applied.reason() == null ? null : applied.reason().name())
                .param("version", applied.version())
                .param("appliedAt", Timestamp.from(applied.appliedAt()))
                .param("effectiveAt", applied.effectiveAt())
                .param("idempotencyKey", applied.idempotencyKey())
                .param("correlationId", caller.correlationId())
                .param("subject", caller.subject())
                .param("jti", caller.jti())
                .update();
    }

    @Override
    public void audit(UUID controlPlaneTenantId, long requestedVersion, LifecycleState requestedState,
            LifecycleState.Reason requestedReason, String outcome, Long appliedVersion, String idempotencyKey,
            CallContext caller) {
        jdbcClient.sql(AUDIT_SQL)
                .param("cpt", controlPlaneTenantId)
                .param("version", requestedVersion)
                .param("state", requestedState.name())
                .param("reason", requestedReason == null ? null : requestedReason.name())
                .param("outcome", outcome)
                .param("appliedVersion", appliedVersion)
                .param("idempotencyKey", idempotencyKey)
                .param("correlationId", caller.correlationId())
                .param("subject", caller.subject())
                .param("jti", caller.jti())
                .update();
    }

    @Override
    public Optional<Status> currentCompanyStatus() {
        return jdbcClient.sql(CURRENT_COMPANY_SQL).query(JdbcLifecycleRepository::status).optional();
    }

    @Override
    public Map<UUID, Status> statusOfOrganizations(Collection<UUID> organizationIds) {
        Map<UUID, Status> states = new HashMap<>();
        jdbcClient.sql(ORGANIZATIONS_SQL)
                .param("organizationIds", "{" + String.join(",", organizationIds.stream().map(UUID::toString).toList())
                        + "}")
                .query((ResultSet rs) -> {
                    states.put(rs.getObject("organization_id", UUID.class), status(rs, 0));
                });
        return states;
    }

    private static Status status(ResultSet rs, int row) throws SQLException {
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        return new Status(LifecycleState.valueOf(rs.getString("state")), reason(rs),
                appliedAt == null ? null : appliedAt.toInstant());
    }

    private static LifecycleState.Reason reason(ResultSet rs) throws SQLException {
        String reason = rs.getString("reason");
        return reason == null ? null : LifecycleState.Reason.valueOf(reason);
    }
}
