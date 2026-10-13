package com.ebim.tms.iam.lifecycle;

import com.ebim.tms.iam.lifecycle.application.LifecycleStore;
import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import com.ebim.tms.shared.security.CompanyScopedAuthentication;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link LifecycleStore} in memory, for the tests that run without a database. The SQL of the production
 * store (V58: constraints, guards, SECURITY DEFINER reads, grants) is exercised by
 * {@code PlatformLifecycleIntegrationTest} where Docker exists.
 *
 * <p>{@link #currentCompanyStatus()} answers like {@code tms.tenant_lifecycle_current_company()}: for
 * the organization of the company scope bound to the current request, and for no other.
 */
public final class InMemoryLifecycleStore implements LifecycleStore {

    public record AuditRow(UUID controlPlaneTenantId, long requestedVersion, LifecycleState requestedState,
            LifecycleState.Reason requestedReason, String outcome, Long appliedVersion, CallContext caller) {}

    private final Map<UUID, ProvisionedTenant> provisioned = new HashMap<>();
    private final Map<UUID, Applied> applied = new HashMap<>();
    public final List<AuditRow> audit = new ArrayList<>();
    public int locks;

    /** A tenant MasterAdmin provisioned here, with its TMS organization. */
    public ProvisionedTenant provision(UUID controlPlaneTenantId, UUID organizationId) {
        ProvisionedTenant tenant = new ProvisionedTenant(controlPlaneTenantId, organizationId);
        provisioned.put(controlPlaneTenantId, tenant);
        return tenant;
    }

    public void clear() {
        applied.clear();
        audit.clear();
        locks = 0;
    }

    @Override
    public Optional<ProvisionedTenant> provisioned(UUID controlPlaneTenantId) {
        return Optional.ofNullable(provisioned.get(controlPlaneTenantId));
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
    public void save(Applied row, CallContext caller) {
        applied.put(row.controlPlaneTenantId(), row);
    }

    @Override
    public void audit(UUID controlPlaneTenantId, long requestedVersion, LifecycleState requestedState,
            LifecycleState.Reason requestedReason, String outcome, Long appliedVersion, String idempotencyKey,
            CallContext caller) {
        audit.add(new AuditRow(controlPlaneTenantId, requestedVersion, requestedState, requestedReason, outcome,
                appliedVersion, caller));
    }

    @Override
    public Optional<Status> currentCompanyStatus() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof CompanyScopedAuthentication scoped)) {
            return Optional.empty();
        }
        return scoped.companyScope().flatMap(scope -> Optional.ofNullable(byOrganization().get(scope.organizationId())));
    }

    @Override
    public Map<UUID, Status> statusOfOrganizations(Collection<UUID> organizationIds) {
        Map<UUID, Status> all = byOrganization();
        Map<UUID, Status> states = new HashMap<>();
        organizationIds.stream().filter(all::containsKey).forEach(id -> states.put(id, all.get(id)));
        return states;
    }

    private Map<UUID, Status> byOrganization() {
        Map<UUID, Status> states = new HashMap<>();
        applied.values().forEach(row -> states.put(row.organizationId(),
                new Status(row.state(), row.reason(), row.appliedAt())));
        return states;
    }
}
