package com.ebim.tms.platform.application;

import com.ebim.tms.platform.domain.AdminProvisioningStatus;
import com.ebim.tms.platform.domain.DeploymentMode;
import com.ebim.tms.platform.domain.PlatformProvisioning;
import com.ebim.tms.platform.domain.PlatformTenant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the control plane gets back, from both the create and the read.
 *
 * <p>"external" is from MasterAdmin's side of the boundary: these are TMS's own identifiers.
 * {@code externalTenantId} is the id of the mapping row, not of the organization or the company -
 * TMS has no single table called tenant, and picking one of the two to impersonate it would make
 * the other look secondary. The organization and company ids are returned beside it precisely so
 * nobody has to guess which one the tenant "is".
 *
 * <p>{@code resources} carries the product-specific facts a control plane cannot derive: the codes
 * TMS generated and the time zone the company ended up with. It carries no business data - no
 * orders, no routes, no vehicles - because provisioning creates none.
 */
public record TenantProvisioningView(
        UUID provisioningId,
        String status,
        boolean replayed,
        UUID controlPlaneTenantId,
        UUID externalTenantId,
        UUID externalOrganizationId,
        UUID externalCompanyId,
        Map<String, Object> resources,
        String adminProvisioningStatus,
        String deploymentMode,
        OffsetDateTime createdAt) {

    /**
     * Built from values rather than from the entity, because the response is stored <em>on</em> the
     * entity: the body has to contain the provisioning id and the administrator's status before the
     * row that will hold it is written. Reading them back off a half-populated entity is how the
     * stored answer ends up with a null id, which is exactly what happened before this signature
     * existed.
     */
    public static TenantProvisioningView of(UUID provisioningId, PlatformTenant tenant,
            AdminProvisioningStatus adminStatus, String organizationCode, String companyCode,
            String timeZone, boolean replayed) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("organizationCode", organizationCode);
        resources.put("companyCode", companyCode);
        resources.put("timeZone", timeZone);
        return new TenantProvisioningView(
                provisioningId,
                "ACTIVE",
                replayed,
                tenant.controlPlaneTenantId(),
                tenant.id(),
                tenant.organizationId(),
                tenant.companyId(),
                resources,
                nameOf(adminStatus),
                nameOf(tenant.deploymentMode()),
                tenant.createdAt());
    }

    private static String nameOf(AdminProvisioningStatus status) {
        return status == null ? AdminProvisioningStatus.NOT_REQUESTED.name() : status.name();
    }

    private static String nameOf(DeploymentMode mode) {
        return mode == null ? DeploymentMode.SHARED.name() : mode.name();
    }
}
