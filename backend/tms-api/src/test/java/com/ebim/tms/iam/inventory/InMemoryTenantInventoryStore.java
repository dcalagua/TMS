package com.ebim.tms.iam.inventory;

import com.ebim.tms.iam.inventory.application.TenantInventoryStore;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.infrastructure.InMemoryPlatformProvisioningRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link TenantInventoryStore} over {@link InMemoryPlatformProvisioningRepository}, so that what the
 * create route writes is what the inventory lists, and what a link writes is what the status route reads -
 * the parity the contract asks for, without a database. The SQL (V76, keyset order, LATERAL company,
 * constraints) is exercised by {@code PlatformTenantInventoryIntegrationTest} where Docker exists.
 *
 * <p>Organizations are ordered by {@link UUID#toString()}, which for canonical lower-case UUIDs is the
 * byte order PostgreSQL sorts {@code uuid} in.
 */
public final class InMemoryTenantInventoryStore implements TenantInventoryStore {

    private final InMemoryPlatformProvisioningRepository provisioning;
    public final Set<UUID> suspendedOrganizations = ConcurrentHashMap.newKeySet();
    public final List<String> locks = new ArrayList<>();
    public final List<NewLink> links = new ArrayList<>();
    /** When set, every read fails with it - the "database is down" case. */
    public RuntimeException failure;

    public InMemoryTenantInventoryStore(InMemoryPlatformProvisioningRepository provisioning) {
        this.provisioning = provisioning;
    }

    public void clear() {
        suspendedOrganizations.clear();
        locks.clear();
        links.clear();
        failure = null;
    }

    @Override
    public List<Tenant> page(UUID after, int limit) {
        if (failure != null) {
            throw failure;
        }
        return provisioning.organizations.keySet().stream()
                .sorted(Comparator.comparing(UUID::toString))
                .filter(id -> after == null || id.toString().compareTo(after.toString()) > 0)
                .limit(limit)
                .map(id -> tenant(id).orElseThrow())
                .toList();
    }

    @Override
    public Optional<Tenant> tenant(UUID organizationId) {
        InMemoryPlatformProvisioningRepository.Organization organization = provisioning.organizations.get(organizationId);
        if (organization == null) {
            return Optional.empty();
        }
        Optional<UUID> tenant = provisioning.tenantOf(organizationId);
        UUID company = tenant.flatMap(provisioning::findByControlPlaneTenantId)
                .map(record -> record.companyId())
                .orElseGet(() -> tenant.isPresent() ? null : provisioning.oldestCompany(organizationId));
        return Optional.of(new Tenant(organizationId, organization.code(), organization.name(),
                !provisioning.inactiveOrganizations.contains(organizationId), company, tenant.orElse(null),
                suspendedOrganizations.contains(organizationId)));
    }

    @Override
    public void lock(String name) {
        locks.add(name);
    }

    @Override
    public Optional<Mapping> mappingOfControlPlaneTenant(UUID controlPlaneTenantId) {
        return provisioning.findByControlPlaneTenantId(controlPlaneTenantId)
                .map(record -> new Mapping(record.provisioningId(), controlPlaneTenantId, record.organizationId()));
    }

    @Override
    public Optional<Mapping> mappingOfOrganization(UUID organizationId) {
        return provisioning.tenantOf(organizationId).flatMap(this::mappingOfControlPlaneTenant);
    }

    @Override
    public UUID insertLink(NewLink link, ProvisioningMetadata metadata) {
        links.add(link);
        return provisioning.link(link.controlPlaneTenantId(), link.organizationId(), link.companyId(),
                link.tenantCode(), link.requestHash());
    }

    @Override
    public void audit(ProvisioningAuditEntry entry) {
        provisioning.recordAudit(entry);
    }
}
