package com.ebim.tms.iam.lifecycle.application;

import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of the lifecycle receiver (V58). Production is {@code infrastructure.JdbcLifecycleRepository};
 * the tests without a database use an in-memory one.
 */
public interface LifecycleStore {

    /** V51's mapping: MasterAdmin tenant to the TMS organization it created. */
    record ProvisionedTenant(UUID controlPlaneTenantId, UUID organizationId) {}

    /** The state applied for one MasterAdmin tenant: one row of {@code tms.platform_tenant_lifecycle}. */
    record Applied(UUID controlPlaneTenantId, UUID organizationId, LifecycleState state,
            LifecycleState.Reason reason, long version, Instant appliedAt, OffsetDateTime effectiveAt,
            String idempotencyKey) {}

    /** The state of one organization as the UI and the security filter read it. */
    record Status(LifecycleState state, LifecycleState.Reason reason, Instant appliedAt) {

        public static final Status ACTIVE = new Status(LifecycleState.ACTIVE, null, null);
    }

    /** Who called: taken from the verified token, never from a header. */
    record CallContext(String correlationId, String subject, String jti) {}

    Optional<ProvisionedTenant> provisioned(UUID controlPlaneTenantId);

    /** Serialises PUTs for one tenant (transaction-scoped advisory lock). */
    void lock(UUID controlPlaneTenantId);

    Optional<Applied> applied(UUID controlPlaneTenantId);

    void save(Applied applied, CallContext caller);

    /**
     * One row of the append-only trail.
     *
     * @param outcome {@code APPLIED}, {@code REPLAYED}, {@code STALE_LIFECYCLE} or {@code VERSION_CONFLICT}
     */
    void audit(UUID controlPlaneTenantId, long requestedVersion, LifecycleState requestedState,
            LifecycleState.Reason requestedReason, String outcome, Long appliedVersion, String idempotencyKey,
            CallContext caller);

    /**
     * The state of the organization of the company the current transaction is scoped to
     * ({@code tms.tenant_lifecycle_current_company()}); empty when unscoped or never set (= ACTIVE).
     */
    Optional<Status> currentCompanyStatus();

    /** The states of these organizations; an organization absent from the map is ACTIVE. */
    Map<UUID, Status> statusOfOrganizations(Collection<UUID> organizationIds);
}
