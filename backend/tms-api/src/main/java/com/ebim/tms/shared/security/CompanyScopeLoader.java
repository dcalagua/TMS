package com.ebim.tms.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a company id into the {@link CompanyScope} a non-membership caller operates in.
 *
 * <p>Declared in {@code shared} and implemented by {@code iam}, exactly like
 * {@link PrincipalLoader}: the dependency points {@code iam -> shared}, so tenancy resolution
 * keeps its repository in the module that owns identity while the integration module - which may
 * not depend on {@code iam} ({@code ModuleBoundaryTest}) - can still obtain a scope.
 *
 * <p>Its one caller is machine authentication. A person's scope is never built this way: it comes
 * from {@link TmsPrincipal#companyScope(UUID)}, which is derived from their memberships, and
 * routing a person through this loader would hand out a company nobody granted them.
 */
public interface CompanyScopeLoader {

    /**
     * The scope for an active company of an active organization, or empty when either is inactive
     * or the company does not exist. Empty is the answer to both, for the reason
     * {@link TmsPrincipal#companyScope} gives: the API must not tell a caller which companies
     * exist.
     *
     * <p>The returned scope carries <b>no permissions</b>. A machine caller holds
     * {@code IntegrationScope}s, not the user-facing {@code Permission} vocabulary, so a
     * {@code hasAuthority('orders.order:manage')} endpoint stays closed to it - which is what
     * keeps the integration surface limited to the endpoints written for it.
     */
    Optional<CompanyScope> loadActiveCompanyScope(UUID companyId);
}
