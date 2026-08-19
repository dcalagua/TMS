package com.ebim.tms.shared.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authentication of a TMS request: a validated Supabase JWT, the resolved
 * {@link TmsPrincipal}, and - once a company has been selected - the {@link CompanyScope}
 * that request operates in.
 *
 * <p><b>Authorities are company-scoped and nothing else.</b> They are the permission codes of
 * the selected company, so {@code hasAuthority('orders.order:manage')} answers "may this
 * caller manage orders <em>in this company</em>", not "somewhere". Before a company is
 * selected there are no authorities at all, which makes every permission-guarded endpoint
 * deny by default rather than fall back to a union across tenants - the union would be a
 * cross-tenant escalation.
 */
public final class TmsAuthenticationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Jwt token;
    private final transient TmsPrincipal principal;
    private final transient CompanyScope companyScope;

    private TmsAuthenticationToken(Jwt token, TmsPrincipal principal, CompanyScope companyScope) {
        super(authorities(companyScope));
        this.token = token;
        this.principal = principal;
        this.companyScope = companyScope;
        setAuthenticated(true);
    }

    /** Authenticated, identified, but not yet bound to a company. */
    public static TmsAuthenticationToken authenticated(Jwt token, TmsPrincipal principal) {
        return new TmsAuthenticationToken(token, principal, null);
    }

    /** The same authentication, bound to a company scope the principal was proven to hold. */
    public TmsAuthenticationToken withCompanyScope(CompanyScope scope) {
        return new TmsAuthenticationToken(token, principal, scope);
    }

    private static Collection<GrantedAuthority> authorities(CompanyScope scope) {
        if (scope == null) {
            return List.of();
        }
        return scope.permissions().stream()
                .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(permission.code()))
                .toList();
    }

    @Override
    public TmsPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return principal.appUserId().toString();
    }

    public Optional<CompanyScope> companyScope() {
        return Optional.ofNullable(companyScope);
    }
}
