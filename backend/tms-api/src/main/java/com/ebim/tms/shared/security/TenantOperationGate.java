package com.ebim.tms.shared.security;

import java.time.Instant;
import java.util.Optional;

/**
 * Has EBIM MasterAdmin suspended the operation of this company's organization ({@code lifecycle.v1},
 * V58, ADR-021)?
 *
 * <p>A port in {@code shared} for the same reason as {@link CommercialAccessGate}: both security chains
 * (users and partner machines) consult it, and the answer comes from {@code iam.lifecycle}, which
 * {@code shared} may not depend on. It is deliberately <b>separate</b> from the commercial gate: under
 * D-14 ruling 1 a commercial fact ({@code appActive=false}) never stops operation; an operational
 * suspension is an explicit, versioned instruction of its own, on its own channel and scopes.
 *
 * <p>It is not RBAC either: no role or permission lifts a suspension, and a suspension grants nothing.
 * Decided from local state only (the last state MasterAdmin applied here), never by calling MasterAdmin.
 *
 * <p>Absent in a deployment or a test slice that does not wire {@code iam.lifecycle}; the chains then
 * behave exactly as before the lifecycle channel existed.
 */
@FunctionalInterface
public interface TenantOperationGate {

    /**
     * Called only for a company-scoped write, on the connection that request will use: the answer
     * concerns {@code scope}'s organization and no other.
     *
     * @return the suspension in force, or empty when the organization may operate
     */
    Optional<Suspension> currentSuspension(CompanyScope scope);

    /**
     * @param reason {@code NON_PAYMENT}, {@code MANUAL} or {@code TERMINATED}
     * @param since  when TMS applied the suspension
     */
    record Suspension(String reason, Instant since) {}
}
