package com.ebim.tms.platform.security;

import com.ebim.tms.platform.application.ProvisioningCaller;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * The authentication a verified control-plane token produces.
 *
 * <p>Its own type on purpose, and deliberately <em>not</em> one of the application's other
 * principals. It is not a {@code TmsAuthenticationToken}, so no code path can mistake a machine for
 * a person or resolve it to an {@code app_user}; and it is not a {@code CompanyScopedAuthentication},
 * so nothing will hand it a company scope. A provisioning caller has no company - it creates them.
 *
 * <p>Its authorities are the token's scopes, unchanged, which is what
 * {@code @PreAuthorize("hasAuthority('tms:tenant:create'))} evaluates. The actor claims travel in the
 * principal for the audit record and grant nothing.
 */
public class PlatformAuthentication extends AbstractAuthenticationToken {

    private final transient ProvisioningCaller caller;

    public PlatformAuthentication(ProvisioningCaller caller, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.caller = caller;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        // The token is not kept: nothing downstream needs to replay it, and a credential held in a
        // security context is a credential that can end up in a heap dump.
        return null;
    }

    @Override
    public ProvisioningCaller getPrincipal() {
        return caller;
    }

    @Override
    public String getName() {
        return caller.subject();
    }
}
