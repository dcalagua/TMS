package com.ebim.tms.shared.security;

import java.util.Optional;

/**
 * An {@link org.springframework.security.core.Authentication} that has been bound to one
 * company, server-side.
 *
 * <p>It exists so that infrastructure which only needs to know "which tenant is this request
 * in" - {@link TenantScopedDataSource} publishing {@code tms.company_id} to PostgreSQL,
 * {@code AuditActorProvider} stamping a change - can ask that question without knowing which
 * kind of caller answered it. There are two:
 *
 * <ul>
 *   <li>{@link TmsAuthenticationToken}, a person holding a Supabase JWT, whose company comes
 *       from the {@code X-Company-Id} header validated against their memberships;</li>
 *   <li>a {@link MachineAuthentication}, a partner integration credential, whose company is a
 *       property of the credential itself and can therefore never be chosen by the caller.</li>
 * </ul>
 *
 * <p>The interface lives in {@code shared} for the reason {@code ModuleBoundaryTest} enforces:
 * {@code shared} must not depend on a business module, so it cannot reference the integration
 * module's token type directly.
 */
public interface CompanyScopedAuthentication {

    /**
     * The company this request operates in, or empty when it is not company-scoped - an
     * unauthenticated request, principal resolution (which runs in order to <em>decide</em> the
     * scope), or a principal-scoped endpoint such as {@code /api/v1/me}.
     */
    Optional<CompanyScope> companyScope();
}
