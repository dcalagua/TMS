package com.ebim.tms.integration.security;

import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.MachineAuthentication;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authentication of an inbound integration request: a verified credential and the single
 * company it is bound to.
 *
 * <p><b>Authorities are integration scopes and nothing else.</b> They are codes like
 * {@code integration.order:write}, which no user-facing endpoint checks, and the
 * {@link CompanyScope} it carries holds an empty permission set - so a partner credential cannot
 * reach {@code /api/v1/orders} even though that endpoint is company-scoped too. The integration
 * surface is limited to the endpoints written for it, by construction rather than by routing.
 *
 * <p>{@code getCredentials()} returns {@code null} on purpose. The presented secret is verified
 * during authentication and then dropped; keeping it on the authentication object would put it in
 * every heap dump and every debug log of the request.
 */
public final class IntegrationAuthenticationToken extends AbstractAuthenticationToken
        implements MachineAuthentication {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient IntegrationPrincipal principal;

    public IntegrationAuthenticationToken(IntegrationPrincipal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(IntegrationPrincipal principal) {
        return principal.scopes().stream()
                .map(IntegrationScope::code)
                .map(code -> (GrantedAuthority) new SimpleGrantedAuthority(code))
                .toList();
    }

    @Override
    public IntegrationPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return principal.clientId();
    }

    @Override
    public Optional<CompanyScope> companyScope() {
        // Never empty: a credential without an active company never becomes a principal.
        return Optional.of(principal.companyScope());
    }

    @Override
    public String machineActorLabel() {
        return principal.actorLabel();
    }

    /** Kept for symmetry with {@code TmsAuthenticationToken}; there is no unscoped variant here. */
    public List<String> scopeCodes() {
        return principal.scopes().stream().map(IntegrationScope::code).toList();
    }
}
