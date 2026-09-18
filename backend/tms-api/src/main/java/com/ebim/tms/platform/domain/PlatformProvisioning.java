package com.ebim.tms.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One provisioning call, keyed by its idempotency key: what was asked, what was answered, and the
 * verified token identity that asked it.
 *
 * <p>This is the audit trail for provisioning, and it is not {@code tms.audit_event} on purpose.
 * That table is company-scoped and {@code AuditRecorder} needs a {@code CompanyScope}; at the moment
 * an organization is created there is no company to scope to, so an entry there would either be
 * impossible or would have to invent a scope. Recording here instead keeps the token subject, the
 * {@code jti} and the control plane's actor claims beside the row they explain.
 *
 * <p>A retry after a failure <em>updates</em> this row rather than inserting a second one. That is
 * what keeps a transient failure from turning the key into a tombstone the caller may never reuse,
 * while {@code uq_platform_provisioning_idempotency_key} still guarantees one row per key.
 */
@Entity
@Table(name = "platform_provisioning")
public class PlatformProvisioning {

    /**
     * Assigned in Java rather than by the database, because the response body stored on this row
     * has to contain this id - and building that body after the insert, only to update the row
     * again, would mean the record and the answer could disagree for one statement.
     */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "control_plane_tenant_id", nullable = false)
    private UUID controlPlaneTenantId;

    @Column(name = "idempotency_key", updatable = false, nullable = false)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "request_snapshot")
    private String requestSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProvisioningStatus status;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "platform_tenant_id")
    private UUID platformTenantId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "admin_app_user_id")
    private UUID adminAppUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_status")
    private AdminProvisioningStatus adminStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_mode")
    private DeploymentMode deploymentMode;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "m2m_subject", nullable = false)
    private String m2mSubject;

    @Column(name = "m2m_jti", nullable = false)
    private String m2mJti;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(name = "received_at", updatable = false, nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "completed_at", nullable = false)
    private OffsetDateTime completedAt;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    protected PlatformProvisioning() {
        // JPA
    }

    private PlatformProvisioning(UUID controlPlaneTenantId, String idempotencyKey, String payloadHash,
            String requestSnapshot, String correlationId, String m2mSubject, String m2mJti,
            String actorId, String actorRole, OffsetDateTime receivedAt) {
        this.id = UUID.randomUUID();
        this.controlPlaneTenantId = controlPlaneTenantId;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.requestSnapshot = requestSnapshot;
        this.correlationId = correlationId;
        this.m2mSubject = m2mSubject;
        this.m2mJti = m2mJti;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.receivedAt = receivedAt;
        this.completedAt = receivedAt;
        this.status = ProvisioningStatus.FAILED;
        this.httpStatus = 500;
        this.durationMs = 0;
    }

    /** A record of an attempt that has not yet been given an outcome. */
    public static PlatformProvisioning attempt(UUID controlPlaneTenantId, String idempotencyKey,
            String payloadHash, String requestSnapshot, String correlationId, String m2mSubject,
            String m2mJti, String actorId, String actorRole, OffsetDateTime receivedAt) {
        return new PlatformProvisioning(controlPlaneTenantId, idempotencyKey, payloadHash,
                requestSnapshot, correlationId, m2mSubject, m2mJti, actorId, actorRole, receivedAt);
    }

    /**
     * Records the tenant this call produced. Called inside the same transaction as the writes it
     * describes, so a row saying SUCCEEDED cannot outlive the organization it names.
     */
    public void succeeded(PlatformTenant tenant, AdminProvisioningStatus adminStatus, String responseBody,
            OffsetDateTime completedAt) {
        this.status = ProvisioningStatus.SUCCEEDED;
        this.httpStatus = 201;
        this.platformTenantId = tenant.id();
        this.organizationId = tenant.organizationId();
        this.companyId = tenant.companyId();
        this.adminAppUserId = tenant.adminAppUserId();
        this.adminStatus = adminStatus;
        this.deploymentMode = tenant.deploymentMode();
        this.responseBody = responseBody;
        this.errorCode = null;
        this.errorSummary = null;
        finish(completedAt);
    }

    /**
     * Records why the call did not produce a tenant. Written in its own transaction, after the
     * business transaction has rolled back - otherwise the record of the failure would roll back
     * with the failure itself and nothing would remain to explain it.
     *
     * @param errorSummary a short business sentence. Never an exception message, a stack frame or SQL
     */
    public void failed(String errorCode, String errorSummary, int httpStatus, OffsetDateTime completedAt) {
        this.status = ProvisioningStatus.FAILED;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.errorSummary = errorSummary;
        this.platformTenantId = null;
        this.organizationId = null;
        this.companyId = null;
        this.adminAppUserId = null;
        this.responseBody = null;
        finish(completedAt);
    }

    /** Re-arms a failed row for another attempt under the same key. */
    public void retrying(UUID controlPlaneTenantId, String payloadHash, String requestSnapshot,
            String correlationId, String m2mSubject, String m2mJti, String actorId, String actorRole) {
        this.controlPlaneTenantId = controlPlaneTenantId;
        this.payloadHash = payloadHash;
        this.requestSnapshot = requestSnapshot;
        this.correlationId = correlationId;
        this.m2mSubject = m2mSubject;
        this.m2mJti = m2mJti;
        this.actorId = actorId;
        this.actorRole = actorRole;
    }

    private void finish(OffsetDateTime at) {
        this.completedAt = at;
        long millis = java.time.Duration.between(receivedAt, at).toMillis();
        this.durationMs = (int) Math.max(0, Math.min(Integer.MAX_VALUE, millis));
    }

    public UUID id() {
        return id;
    }

    public UUID controlPlaneTenantId() {
        return controlPlaneTenantId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public ProvisioningStatus status() {
        return status;
    }

    public UUID platformTenantId() {
        return platformTenantId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID adminAppUserId() {
        return adminAppUserId;
    }

    public AdminProvisioningStatus adminStatus() {
        return adminStatus;
    }

    public DeploymentMode deploymentMode() {
        return deploymentMode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String errorCode() {
        return errorCode;
    }

    public String correlationId() {
        return correlationId;
    }

    public OffsetDateTime receivedAt() {
        return receivedAt;
    }

    public boolean succeeded() {
        return status == ProvisioningStatus.SUCCEEDED;
    }
}
