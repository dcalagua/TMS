package com.ebim.tms.iam.inventory.application;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of {@code tenant-inventory.v1} (V76). Production is
 * {@code infrastructure.JdbcTenantInventoryRepository}; the tests without a database use an in-memory one.
 *
 * <p>Reads {@code tms.organization}, {@code tms.company}, V51's {@code platform_provisioning_request} and
 * V58's {@code platform_tenant_lifecycle}; writes exactly one thing, a {@code LINKED} provisioning row, and
 * the audit trail. On the owning connection: the MasterAdmin caller is never company-scoped.
 */
public interface TenantInventoryStore {

    /**
     * One TMS tenant (an organization, ADR-003) as the inventory reports it.
     *
     * @param companyId            the company of its provisioning row when it has one, otherwise its oldest
     *                             company - the one a link would record, so the id does not change on linking
     * @param controlPlaneTenantId the MasterAdmin tenant mapped to it by V51/V76, or null
     * @param suspended            lifecycle.v1 (V58) holds it SUSPENDED
     */
    record Tenant(UUID organizationId, String code, String name, boolean active, UUID companyId,
            UUID controlPlaneTenantId, boolean suspended) {}

    /** A row of {@code platform_provisioning_request}: which MasterAdmin tenant maps to which organization. */
    record Mapping(UUID provisioningId, UUID controlPlaneTenantId, UUID organizationId) {}

    /** The functional columns of a {@code LINKED} provisioning row. */
    record NewLink(UUID controlPlaneTenantId, UUID organizationId, UUID companyId, String tenantCode,
            String requestHash, String productCode, String contractVersion, String environment) {}

    /** Up to {@code limit} tenants with an organization id greater than {@code after} (null: from the start), by id. */
    List<Tenant> page(UUID after, int limit);

    Optional<Tenant> tenant(UUID organizationId);

    /** Transaction-scoped advisory lock, in the namespace tenant creation uses. */
    void lock(String name);

    Optional<Mapping> mappingOfControlPlaneTenant(UUID controlPlaneTenantId);

    Optional<Mapping> mappingOfOrganization(UUID organizationId);

    /** Writes the {@code LINKED} row; returns its id. */
    UUID insertLink(NewLink link, ProvisioningMetadata metadata);

    void audit(ProvisioningAuditEntry entry);
}
