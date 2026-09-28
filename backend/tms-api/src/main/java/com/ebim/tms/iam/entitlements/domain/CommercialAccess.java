package com.ebim.tms.iam.entitlements.domain;

/**
 * Whether an organization may operate TMS commercially, decided ONLY from local facts: whether
 * MasterAdmin provisioned it, the enforcement mode, and the {@code appActive} of the last snapshot
 * applied here. MasterAdmin is never asked; if it is down the last snapshot keeps deciding, without
 * expiry (CCP spec section 8.4).
 *
 * <p>This is not RBAC. A permission says what a member may do inside a company; this says whether the
 * organization's contract is live at all. Neither implies the other.
 */
public record CommercialAccess(boolean allowed, Reason reason, EnforcementMode mode) {

    public enum Reason {
        /** Not provisioned by MasterAdmin (created before the control plane): not under its authority. */
        NOT_UNDER_CONTROL_PLANE,
        /** LEGACY or SHADOW: the snapshot is stored and compared, and decides nothing. */
        NOT_ENFORCED,
        /** The applied snapshot says the application is active. */
        APP_ACTIVE,
        /** The applied snapshot says {@code appActive=false}: operation is suspended, no data is touched. */
        APP_INACTIVE,
        /** DUAL_READ with no snapshot applied yet: legacy decides (everything operates) and it is logged. */
        NO_SNAPSHOT_LEGACY_FALLBACK,
        /** PRIMARY with no snapshot applied: nothing is granted until MasterAdmin sends one. */
        NO_SNAPSHOT
    }

    /**
     * @param underControlPlane the organization was provisioned by MasterAdmin
     * @param appliedAppActive  {@code appActive} of the applied snapshot, or {@code null} when none
     */
    public static CommercialAccess decide(boolean underControlPlane, EnforcementMode mode, Boolean appliedAppActive) {
        if (!underControlPlane) {
            return new CommercialAccess(true, Reason.NOT_UNDER_CONTROL_PLANE, mode);
        }
        if (!mode.snapshotDecides()) {
            return new CommercialAccess(true, Reason.NOT_ENFORCED, mode);
        }
        if (appliedAppActive == null) {
            return mode == EnforcementMode.DUAL_READ
                    ? new CommercialAccess(true, Reason.NO_SNAPSHOT_LEGACY_FALLBACK, mode)
                    : new CommercialAccess(false, Reason.NO_SNAPSHOT, mode);
        }
        return appliedAppActive
                ? new CommercialAccess(true, Reason.APP_ACTIVE, mode)
                : new CommercialAccess(false, Reason.APP_INACTIVE, mode);
    }
}
