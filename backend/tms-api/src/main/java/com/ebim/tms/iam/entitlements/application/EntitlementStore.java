package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.domain.AppliedStatus;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persistence of the receiver (V52). Production is {@code infrastructure.JdbcEntitlementRepository};
 * the tests without a database use an in-memory one.
 */
public interface EntitlementStore {

    /** Scope key of the product-wide enforcement mode. A tenant row, when present, overrides it. */
    String PRODUCT_SCOPE = "PRODUCT";

    /** V51's mapping: MasterAdmin tenant to the TMS organization it created. */
    record ProvisionedTenant(UUID controlPlaneTenantId, UUID organizationId) {}

    record Applied(UUID controlPlaneTenantId, UUID organizationId, String productCode, long version,
            String checksum, AppliedStatus status, boolean appActive, List<String> unknownCapabilities,
            Instant appliedAt, ObjectNode snapshot) {}

    /** Who called: taken from the verified token, never from a header. */
    record CallContext(String correlationId, String subject, String jti) {}

    /** Legacy decision against the snapshot's, recorded while the snapshot does not decide alone. */
    record ShadowDiff(UUID controlPlaneTenantId, UUID organizationId, String kind, EnforcementMode mode,
            boolean legacyDecision, boolean snapshotDecision, long snapshotVersion) {}

    /**
     * The commercial access facts of the organization of the company the current transaction is
     * scoped to ({@code tms.commercial_access_current_company()}); empty when unscoped.
     */
    record AccessFacts(boolean underControlPlane, EnforcementMode mode, Boolean appActive) {}

    Optional<ProvisionedTenant> provisioned(UUID controlPlaneTenantId);

    Optional<ProvisionedTenant> provisionedOrganization(UUID organizationId);

    /** @return false when that {@code (issuer, jti)} was already used: the request is a replay. */
    boolean consumeJti(String issuer, String jti, Instant expiresAt);

    /** Serialises PUTs for one tenant (transaction-scoped advisory lock). */
    void lock(UUID controlPlaneTenantId);

    Optional<Applied> applied(UUID controlPlaneTenantId);

    void save(Applied applied, CallContext caller);

    void audit(UUID controlPlaneTenantId, Long version, String checksum, String outcome, CallContext caller,
            Map<String, Object> detail);

    /** Effective mode: the tenant's row if it has one, else the product's; with neither, LEGACY. */
    EnforcementMode mode(UUID controlPlaneTenantId);

    void shadowDiff(ShadowDiff diff);

    /** Safe on the runtime role: reveals only the caller's own organization (V52 section 6). */
    Optional<AccessFacts> currentCompanyAccess();
}
