package com.ebim.tms.iam.provisioning.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The scope checks of {@code tenant-inventory.v1}, for the chain and for {@code @PreAuthorize}
 * ({@code @platformInventoryAccess.mayList(authentication)}).
 *
 * <p>The scopes come from {@link PlatformInventoryProperties}, so they cannot be written into an
 * annotation the way {@link PlatformScopes} are. A route whose scope is not configured lets an
 * authenticated MasterAdmin token reach the handler, and the handler answers {@code 503 ..._NOT_CONFIGURED}
 * before doing anything: "not configured" must read as such, not as a missing scope the caller could
 * go looking for.
 */
@Component("platformInventoryAccess")
public class PlatformInventoryAccess {

    private final PlatformInventoryProperties properties;

    public PlatformInventoryAccess(PlatformInventoryProperties properties) {
        this.properties = properties;
    }

    public boolean listConfigured() {
        return properties.listScope().isPresent();
    }

    public boolean linkConfigured() {
        return properties.adoptScope().isPresent();
    }

    /** May reach {@code GET /tenants}: holds the configured scope, or the route is closed (503 follows). */
    public boolean mayList(Authentication authentication) {
        return allows(authentication, properties.listScope());
    }

    /** May reach {@code PUT /tenants/{id}/link}: holds the configured scope, or the route is closed. */
    public boolean mayLink(Authentication authentication) {
        return allows(authentication, properties.adoptScope());
    }

    private static boolean allows(Authentication authentication, Optional<String> scope) {
        if (!(authentication instanceof PlatformProvisioningAuthentication)) {
            return false;
        }
        return scope.map(required -> holds(authentication, required)).orElse(true);
    }

    private static boolean holds(Authentication authentication, String scope) {
        String authority = PlatformScopes.AUTHORITY_PREFIX + scope;
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
