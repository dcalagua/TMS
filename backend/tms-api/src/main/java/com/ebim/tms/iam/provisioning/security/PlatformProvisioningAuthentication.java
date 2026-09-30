package com.ebim.tms.iam.provisioning.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * An authenticated MasterAdmin call.
 *
 * <p>Deliberately NOT a {@code CompanyScopedAuthentication}: provisioning creates the company, so
 * it cannot already be scoped to one. That is also what keeps {@code TenantScopedDataSource} from
 * switching the connection to {@code tms_app} for this path - the identity tables and the V51 tables
 * are written by the owning connection, exactly as {@code PrincipalResolutionService} reads them.
 *
 * <p>Authorities are the token's scopes as {@code SCOPE_<scope>} and nothing else. {@code actor_role}
 * never becomes an authority: if it did, TMS's access policy would be written by another system's
 * role catalogue.
 */
public final class PlatformProvisioningAuthentication extends AbstractAuthenticationToken {

    private final PlatformCaller caller;

    private PlatformProvisioningAuthentication(PlatformCaller caller, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.caller = caller;
        setAuthenticated(true);
    }

    /** Converts a token that already passed every validator of {@link PlatformM2mJwtDecoders}. */
    public static PlatformProvisioningAuthentication from(Jwt token) {
        PlatformCaller caller = new PlatformCaller(
                PlatformCaller.bounded(token.getSubject()),
                PlatformCaller.bounded(token.getId()),
                PlatformCaller.bounded(token.getClaim("actor_id")),
                PlatformCaller.bounded(token.getClaim("actor_role")));
        return new PlatformProvisioningAuthentication(caller, PlatformScopes.of(token).stream()
                .map(scope -> new SimpleGrantedAuthority(PlatformScopes.AUTHORITY_PREFIX + scope))
                .toList());
    }

    @Override
    public Object getCredentials() {
        // The token is not kept: nothing downstream needs it, and a credential that is not held
        // cannot be logged by accident.
        return null;
    }

    @Override
    public PlatformCaller getPrincipal() {
        return caller;
    }

    @Override
    public String getName() {
        return caller.subject();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlatformProvisioningAuthentication that
                && super.equals(that) && caller.equals(that.caller);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + caller.hashCode();
    }
}
