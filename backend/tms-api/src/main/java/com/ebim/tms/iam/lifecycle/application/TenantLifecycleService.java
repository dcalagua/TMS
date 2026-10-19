package com.ebim.tms.iam.lifecycle.application;

import com.ebim.tms.iam.lifecycle.application.LifecycleStore.Status;
import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TenantOperationGate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operational state of TMS organizations, read from the LOCAL lifecycle row only (V58): the answer
 * of the security chains' {@link TenantOperationGate} and of {@code /api/v1/me}.
 *
 * <p>Never asks MasterAdmin. If it is down, the last applied state keeps deciding, without expiry: a
 * suspended organization stays suspended and an active one keeps working.
 *
 * <p>Separate from {@code CommercialEntitlementService} on purpose (D-14): {@code appActive=false}
 * withdraws the commercial surface, a {@code SUSPENDED} lifecycle stops writes. Neither implies the
 * other, and neither is RBAC.
 */
@Service
public class TenantLifecycleService implements TenantOperationGate {

    private final LifecycleStore store;

    public TenantLifecycleService(LifecycleStore store) {
        this.store = store;
    }

    /**
     * Read through V58's SECURITY DEFINER function, keyed on the company the connection is scoped to -
     * which is {@code scope}'s by construction, as the filter runs after the scope is bound.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<Suspension> currentSuspension(CompanyScope scope) {
        return store.currentCompanyStatus()
                .filter(status -> status.state() == LifecycleState.SUSPENDED)
                .map(status -> new Suspension(status.reason() == null ? null : status.reason().name(),
                        status.appliedAt()));
    }

    /** For the UI banner: every organization absent from the store is {@link Status#ACTIVE}. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<UUID, Status> statusOf(Collection<UUID> organizationIds) {
        return organizationIds.isEmpty() ? Map.of() : store.statusOfOrganizations(organizationIds);
    }
}
