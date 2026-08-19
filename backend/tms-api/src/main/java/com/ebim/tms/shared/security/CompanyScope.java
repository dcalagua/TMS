package com.ebim.tms.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * One company the caller may act in, together with the permissions they hold there.
 *
 * <p>Two roles, one type:
 *
 * <ol>
 *   <li>inside {@link TmsPrincipal} it is an element of "the companies this caller may select";</li>
 *   <li>as a controller method parameter it is <em>the</em> company the current request operates
 *       in, already validated against the caller's active memberships.</li>
 * </ol>
 *
 * <p>Because the only way to obtain one is server-side resolution
 * ({@link CompanyScopeArgumentResolver}), a service that takes a {@code CompanyScope} cannot
 * be called with a company the caller has no membership in. That is the point: the tenant is
 * a resolved fact, not a method argument the caller chose.
 *
 * @param permissions the union of the permissions granted by every active membership that
 *     applies to this company - the company-scoped membership and any organization-wide one
 */
public record CompanyScope(
        UUID companyId,
        String companyCode,
        String companyName,
        String timeZone,
        UUID organizationId,
        String organizationCode,
        String organizationName,
        Set<Permission> permissions) {

    public CompanyScope {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
