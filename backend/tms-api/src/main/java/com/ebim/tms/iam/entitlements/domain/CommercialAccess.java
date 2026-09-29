package com.ebim.tms.iam.entitlements.domain;

/**
 * The commercial standing of an organization in TMS, decided ONLY from local facts: whether
 * MasterAdmin provisioned it, the enforcement mode, and the {@code appActive} of the last snapshot
 * applied here. MasterAdmin is never asked; if it is down the last snapshot keeps deciding, without
 * expiry (CCP spec section 8.4).
 *
 * <p><b>Commercial is not operational access</b> (D-14 ruling 1, 2026-09-29): {@code appActive=false}
 * - or PRIMARY with no snapshot yet - withdraws the commercial SaaS surface ({@link #commercialActive}
 * is {@code false}: no sellable capability, no commercial limit), and the organization keeps operating
 * TMS. A full operational shutdown is a separate policy that does not exist yet; until it does,
 * {@link #operationAllowed()} is {@code true} for every decision, and the reason stays observable for
 * diagnostics.
 *
 * <p>This is not RBAC either. A permission says what a member may do inside a company; this says what
 * the organization's contract grants. Neither implies the other.
 */
public record CommercialAccess(boolean commercialActive, Reason reason, EnforcementMode mode) {

    public enum Reason {
        /** Not provisioned by MasterAdmin (created before the control plane): not under its authority. */
        NOT_UNDER_CONTROL_PLANE,
        /** LEGACY or SHADOW: the snapshot is stored and compared, and decides nothing. */
        NOT_ENFORCED,
        /** The applied snapshot says the application is active. */
        APP_ACTIVE,
        /** The applied snapshot says {@code appActive=false}: the commercial surface is off; operation continues. */
        APP_INACTIVE,
        /** DUAL_READ with no snapshot applied yet: legacy decides and it is logged. */
        NO_SNAPSHOT_LEGACY_FALLBACK,
        /** PRIMARY with no snapshot applied: nothing commercial is granted until MasterAdmin sends one. */
        NO_SNAPSHOT
    }

    /**
     * May the organization operate TMS? Always, under D-14 ruling 1: no commercial fact withholds
     * operation. The method exists so that the security chains ask this question - not
     * {@link #commercialActive} - and a future operational-shutdown policy has one place to answer it.
     */
    public boolean operationAllowed() {
        return true;
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
