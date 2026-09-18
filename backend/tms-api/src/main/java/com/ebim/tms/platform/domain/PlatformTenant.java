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
 * The mapping from one EBIM MasterAdmin tenant to the TMS organization and company it became.
 *
 * <p>This row is the answer to "which tenant is this?", and it is deliberately a row rather than an
 * assumption. Nothing in TMS treats {@code controlPlaneTenantId} as an internal id: the control
 * plane owns its own id space and may renumber, merge or reissue in ways TMS will never see, so the
 * only safe relationship between the two spaces is one that is written down and unique
 * ({@code uq_platform_tenant_control_plane}).
 *
 * <p>The row exists only when provisioning succeeded. Together with the unique constraints on
 * {@code organization_id} and {@code company_id} it is what makes provisioning the same tenant
 * twice impossible - including two callers arriving at the same instant with different idempotency
 * keys, where the database refuses the second rather than the application noticing in time.
 */
@Entity
@Table(name = "platform_tenant")
public class PlatformTenant {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "control_plane_tenant_id", updatable = false, nullable = false)
    private UUID controlPlaneTenantId;

    @Column(name = "organization_id", updatable = false, nullable = false)
    private UUID organizationId;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "admin_app_user_id", updatable = false)
    private UUID adminAppUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_mode", updatable = false, nullable = false)
    private DeploymentMode deploymentMode;

    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PlatformTenant() {
        // JPA
    }

    public PlatformTenant(UUID controlPlaneTenantId, UUID organizationId, UUID companyId,
            UUID adminAppUserId, DeploymentMode deploymentMode, OffsetDateTime now) {
        this.controlPlaneTenantId = controlPlaneTenantId;
        this.organizationId = organizationId;
        this.companyId = companyId;
        this.adminAppUserId = adminAppUserId;
        this.deploymentMode = deploymentMode;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID controlPlaneTenantId() {
        return controlPlaneTenantId;
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

    public DeploymentMode deploymentMode() {
        return deploymentMode;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
