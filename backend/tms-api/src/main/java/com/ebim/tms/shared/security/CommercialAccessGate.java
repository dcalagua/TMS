package com.ebim.tms.shared.security;

/**
 * May the organization of this company operate TMS under its commercial contract?
 *
 * <p>A port in {@code shared} for the reason {@code ModuleBoundaryTest} enforces: both security chains
 * (users and partner machines) consult it, and the answer comes from {@code iam.entitlements}, which
 * {@code shared} may not depend on. It is <b>not</b> RBAC - no permission, role or capability grants or
 * withholds it - and it is decided from local state only (the last MasterAdmin snapshot applied here),
 * never by calling MasterAdmin.
 *
 * <p>Absent in a deployment or a test slice that does not wire {@code iam.entitlements}; the chains
 * then behave exactly as before the control plane existed.
 */
@FunctionalInterface
public interface CommercialAccessGate {

    /**
     * Called only for a company-scoped request, on the connection that request will use: the answer
     * must concern {@code scope}'s organization and no other.
     */
    Verdict check(CompanyScope scope);

    /** @param reason machine-readable cause, logged; never shown to the caller beyond the problem code */
    record Verdict(boolean allowed, String reason) {

        public static final Verdict ALLOWED = new Verdict(true, "ALLOWED");

        public static Verdict suspended(String reason) {
            return new Verdict(false, reason);
        }
    }
}
