package com.ebim.tms.iam.entitlements;

import com.ebim.tms.iam.entitlements.application.EntitlementStore;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link EntitlementStore} in memory, for the receiver tests that run without a database. The SQL of
 * the production store is exercised by {@code PlatformEntitlementsIntegrationTest} where Docker exists.
 */
public final class InMemoryEntitlementStore implements EntitlementStore {

    public record AuditRow(UUID controlPlaneTenantId, Long version, String checksum, String outcome,
            CallContext caller, Map<String, Object> detail) {}

    private final Map<UUID, ProvisionedTenant> provisioned = new HashMap<>();
    private final Map<UUID, Applied> applied = new HashMap<>();
    private final Set<String> jtis = new HashSet<>();
    private final Map<String, EnforcementMode> modes = new HashMap<>();
    public final List<AuditRow> audit = new ArrayList<>();
    public final List<ShadowDiff> diffs = new ArrayList<>();
    public int locks;
    /** What {@code tms.commercial_access_current_company()} would return for the scoped company. */
    public Optional<AccessFacts> currentCompany = Optional.empty();

    public InMemoryEntitlementStore(EnforcementMode productMode) {
        modes.put(PRODUCT_SCOPE, productMode);
    }

    /** A tenant MasterAdmin provisioned here, with its TMS organization. */
    public ProvisionedTenant provision(UUID controlPlaneTenantId) {
        return provision(controlPlaneTenantId, UUID.randomUUID());
    }

    public ProvisionedTenant provision(UUID controlPlaneTenantId, UUID organizationId) {
        ProvisionedTenant tenant = new ProvisionedTenant(controlPlaneTenantId, organizationId);
        provisioned.put(controlPlaneTenantId, tenant);
        return tenant;
    }

    public void setMode(String scopeKey, EnforcementMode mode) {
        modes.put(scopeKey, mode);
    }

    /** The mode row stored for {@code scopeKey} ({@code PRODUCT} or a tenant id), if there is one. */
    public Optional<EnforcementMode> modeAt(String scopeKey) {
        return Optional.ofNullable(modes.get(scopeKey));
    }

    @Override
    public Optional<ProvisionedTenant> provisioned(UUID controlPlaneTenantId) {
        return Optional.ofNullable(provisioned.get(controlPlaneTenantId));
    }

    @Override
    public Optional<ProvisionedTenant> provisionedOrganization(UUID organizationId) {
        return provisioned.values().stream().filter(t -> t.organizationId().equals(organizationId)).findFirst();
    }

    @Override
    public boolean consumeJti(String issuer, String jti, Instant expiresAt) {
        return jtis.add(issuer + "|" + jti);
    }

    @Override
    public void lock(UUID controlPlaneTenantId) {
        locks++;
    }

    @Override
    public Optional<Applied> applied(UUID controlPlaneTenantId) {
        return Optional.ofNullable(applied.get(controlPlaneTenantId));
    }

    @Override
    public void save(Applied snapshot, CallContext caller) {
        applied.put(snapshot.controlPlaneTenantId(), snapshot);
    }

    @Override
    public void audit(UUID controlPlaneTenantId, Long version, String checksum, String outcome, CallContext caller,
            Map<String, Object> detail) {
        audit.add(new AuditRow(controlPlaneTenantId, version, checksum, outcome, caller, Map.copyOf(detail)));
    }

    @Override
    public EnforcementMode mode(UUID controlPlaneTenantId) {
        EnforcementMode tenant = controlPlaneTenantId == null ? null : modes.get(controlPlaneTenantId.toString());
        return tenant != null ? tenant : modes.getOrDefault(PRODUCT_SCOPE, EnforcementMode.LEGACY);
    }

    @Override
    public void shadowDiff(ShadowDiff diff) {
        diffs.add(diff);
    }

    @Override
    public Optional<AccessFacts> currentCompanyAccess() {
        return currentCompany;
    }
}
