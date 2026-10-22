package com.ebim.tms.orders.application;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopedAuthentication;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * An anonymous tracking-link view bound to the link's company (ADR-029 decision 3, T4).
 *
 * <p>Installed only by {@link PublicTrackingService}, only after the token resolved, and only for the
 * scoped half of the read. Its whole purpose is {@link CompanyScopedAuthentication}: with it on the
 * context, {@code TenantScopedDataSource} switches the connection to {@code tms_app} and publishes this
 * company to {@code tms.company_id}, so Row Level Security filters every read of the projection to the
 * link's tenant - the order, its trips, the master data names.
 *
 * <p>It carries <b>no authorities</b> and is not a {@code MachineAuthentication}: nothing guarded by
 * {@code hasAuthority} opens for it, and nothing can record an audit event as it. It is never built from
 * anything the caller supplied other than the token, whose company was read from the database.
 */
final class PublicTrackingAuthentication extends AbstractAuthenticationToken implements CompanyScopedAuthentication {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String PRINCIPAL = "public-tracking-link";

    private final transient CompanyScope scope;

    private PublicTrackingAuthentication(CompanyScope scope) {
        super(List.of());
        this.scope = Objects.requireNonNull(scope, "scope");
        setAuthenticated(true);
    }

    /** Runs {@code work} bound to {@code scope} and restores the previous context, on success and failure. */
    static <T> T runAs(CompanyScope scope, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PublicTrackingAuthentication(scope));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @Override
    public Object getPrincipal() {
        return PRINCIPAL;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return PRINCIPAL;
    }

    @Override
    public Optional<CompanyScope> companyScope() {
        return Optional.of(scope);
    }
}
