package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.application.EntitlementStore.Applied;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.ProvisionedTenant;
import com.ebim.tms.iam.entitlements.domain.CommercialAccess;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.entitlements.domain.EntitlementSnapshot;
import com.ebim.tms.iam.entitlements.domain.SnapshotValidator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The commercial entitlements of a TMS tenant (an organization), read from the LOCAL snapshot only.
 *
 * <p>Deliberately separate from RBAC ({@code Permission}, {@code CompanyScope}): a permission says
 * what a member may do; this says what the organization contracted. No call goes to MasterAdmin -
 * if it is down, the last applied snapshot keeps deciding, without expiry.
 *
 * <p>What TMS enforces today is only {@link #access}: TMS registers no sellable capability and no
 * limit ({@code TmsCapabilityRegistry}). {@link #capabilityEnabled} and {@link #limit} answer for any
 * code the receiver knows and fail closed for everything else, so a sellable added later is enforced
 * the day it is registered, and a code nobody registered is never granted by accident.
 */
@Service
public class CommercialEntitlementService {

    private static final Logger log = LoggerFactory.getLogger(CommercialEntitlementService.class);

    private final EntitlementStore store;
    private final ReceiverProfile profile;

    public CommercialEntitlementService(EntitlementStore store, ReceiverProfile profile) {
        this.store = store;
        this.profile = profile;
    }

    /** May this organization operate TMS at all? See {@link CommercialAccess} for the rules. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CommercialAccess access(UUID organizationId) {
        Optional<ProvisionedTenant> tenant = store.provisionedOrganization(organizationId);
        if (tenant.isEmpty()) {
            return CommercialAccess.decide(false, EnforcementMode.LEGACY, null);
        }
        UUID controlPlaneTenantId = tenant.get().controlPlaneTenantId();
        EnforcementMode mode = store.mode(controlPlaneTenantId);
        Boolean appActive = mode.snapshotDecides()
                ? store.applied(controlPlaneTenantId).map(Applied::appActive).orElse(null)
                : null;
        CommercialAccess access = CommercialAccess.decide(true, mode, appActive);
        if (access.reason() == CommercialAccess.Reason.NO_SNAPSHOT_LEGACY_FALLBACK) {
            log.warn("Entitlements DUAL_READ with no applied snapshot for organization {}: legacy decides",
                    organizationId);
        }
        return access;
    }

    /**
     * {@link #access} for the company the current request is scoped to, read through V52's SECURITY
     * DEFINER function so that it works on the runtime role and reveals nothing about another
     * organization. No row (not scoped, or unknown company) is "not under the control plane".
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CommercialAccess accessForCurrentCompany() {
        return store.currentCompanyAccess()
                .map(facts -> CommercialAccess.decide(facts.underControlPlane(), facts.mode(),
                        facts.mode().snapshotDecides() ? facts.appActive() : null))
                .orElseGet(() -> CommercialAccess.decide(false, EnforcementMode.LEGACY, null));
    }

    /**
     * Is this sellable capability granted to the organization, when the snapshot is what decides?
     *
     * <p>{@code false} for a code the receiver does not know, for a grant scoped to explicit
     * MasterAdmin company ids (not translatable into TMS companies), and when the mode leaves the
     * decision to legacy - where no TMS sellable exists to be granted.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean capabilityEnabled(UUID organizationId, String code) {
        if (!profile.knownCodes().contains(code)) {
            return false;
        }
        return decidingSnapshot(organizationId)
                .flatMap(s -> s.capabilities().stream().filter(c -> c.code().equals(code)).findFirst())
                .map(c -> c.enabled() && !c.explicitCompanies())
                .orElse(false);
    }

    /**
     * The commercial limit granted for {@code code}, when the snapshot is what decides.
     *
     * <p>Empty means "no commercial limit granted" - never "unlimited". A caller enforcing a LIMIT must
     * therefore refuse when this is empty in DUAL_READ/PRIMARY for a code it has registered.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<EntitlementSnapshot.Limit> limit(UUID organizationId, String code) {
        if (!profile.knownCodes().contains(code)) {
            return Optional.empty();
        }
        return decidingSnapshot(organizationId)
                .flatMap(s -> s.limits().stream().filter(l -> l.code().equals(code)).findFirst())
                .filter(l -> !l.explicitCompanies());
    }

    private Optional<EntitlementSnapshot> decidingSnapshot(UUID organizationId) {
        return store.provisionedOrganization(organizationId)
                .filter(t -> store.mode(t.controlPlaneTenantId()).snapshotDecides())
                .flatMap(t -> store.applied(t.controlPlaneTenantId()))
                .map(a -> SnapshotValidator.validate(a.snapshot()));
    }
}
