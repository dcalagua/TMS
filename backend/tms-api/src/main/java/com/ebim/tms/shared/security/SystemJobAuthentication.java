package com.ebim.tms.shared.security;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A scheduled job acting inside one company (V65, ADR-023: the accrual sweep).
 *
 * <p>A scheduled thread has no security context, so {@link TenantScopedDataSource} leaves its
 * connections on the owner role and Row Level Security filters nothing. That is right for the one
 * question that crosses tenants ("which companies have work?") and wrong for the work itself. This
 * token is what a job installs while it works for one company: the connection becomes {@code tms_app}
 * bound to that company, as for any request, and the audit trail names the job as a machine actor
 * instead of throwing for want of a person.
 *
 * <p>It carries <b>no permissions</b> and no authorities, like {@link CompanyScopeLoader}'s scopes:
 * nothing guarded by {@code hasAuthority} opens for it. It is never built from a request.
 */
public final class SystemJobAuthentication extends AbstractAuthenticationToken implements MachineAuthentication {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient CompanyScope scope;
    private final String label;

    public SystemJobAuthentication(CompanyScope scope, String label) {
        super(List.of());
        this.scope = Objects.requireNonNull(scope, "scope");
        this.label = Objects.requireNonNull(label, "label");
        setAuthenticated(true);
    }

    /**
     * Runs {@code work} with this job's company as the current tenant and restores whatever context
     * was there before - on success and on failure - so a pooled scheduler thread never carries a
     * tenant into its next task.
     */
    public static <T> T runAs(CompanyScope scope, String label, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new SystemJobAuthentication(scope, label));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @Override
    public Object getPrincipal() {
        return label;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return label;
    }

    @Override
    public Optional<CompanyScope> companyScope() {
        return Optional.of(scope);
    }

    @Override
    public String machineActorLabel() {
        return label;
    }
}
