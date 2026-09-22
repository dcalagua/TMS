package com.ebim.tms.shared.security;

import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Outcome;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link TenantRuntimeRoleCheck}'s verdict as the health contributor
 * {@code tenantRuntimeRole}, so that an instance whose connection cannot enter the ADR-005 runtime
 * role - and on which, therefore, every company-scoped request fails - is taken out of rotation
 * instead of being advertised as ready. See {@link TenantRuntimeRoleCheck} for the evidence and
 * for when the verdict is re-evaluated.
 *
 * <p>Spring Boot does not put a custom contributor into the {@code readiness} group on its own;
 * that takes {@code management.endpoint.health.group.readiness.include}, which lives in
 * {@code application.yml}. It is never meant for liveness: restarting the process cannot create a
 * role or grant one.
 *
 * <table>
 *   <caption>Verdict to status</caption>
 *   <tr><th>Verdict</th><th>Status</th></tr>
 *   <tr><td>{@code RLS_IN_FORCE}</td><td>UP</td></tr>
 *   <tr><td>{@code RUNTIME_ROLE_UNREACHABLE}</td><td>DOWN</td></tr>
 *   <tr><td>{@code CONNECTED_AS_RUNTIME_ROLE}</td><td>UP - requests succeed; the log carries the
 *       warning</td></tr>
 *   <tr><td>none yet (database never reachable, no DataSource)</td><td>UNKNOWN - which the
 *       default aggregation ranks below UP, so it neither holds an instance out nor claims one is
 *       fine; a database outage is the database indicator's to report</td></tr>
 * </table>
 *
 * <p><b>No details, ever.</b> The response carries a status and nothing else - no role name, no
 * SQL, no exception - whatever {@code show-details} a profile sets, because a health endpoint is
 * reachable without authentication. The role names and the {@code GRANT} that fixes the problem
 * go to the log, where {@link TenantRuntimeRoleCheck} writes them.
 */
@Component
public class TenantRuntimeRoleHealthIndicator implements HealthIndicator {

    private final TenantRuntimeRoleCheck check;

    public TenantRuntimeRoleHealthIndicator(TenantRuntimeRoleCheck check) {
        this.check = check;
    }

    @Override
    public Health health() {
        return toHealth(check.currentOutcome());
    }

    static Health toHealth(Optional<Outcome> outcome) {
        if (outcome.isEmpty()) {
            return Health.unknown().build();
        }
        return switch (outcome.get()) {
            case RLS_IN_FORCE, CONNECTED_AS_RUNTIME_ROLE -> Health.up().build();
            case RUNTIME_ROLE_UNREACHABLE -> Health.down().build();
        };
    }
}
