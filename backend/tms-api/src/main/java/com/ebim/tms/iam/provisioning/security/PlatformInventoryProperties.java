package com.ebim.tms.iam.provisioning.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two scopes of {@code tenant-inventory.v1}, under {@code tms.platform-inventory}.
 *
 * <p>Unlike {@link PlatformScopes}, these are configuration and not code, because the suite decides
 * them per integration (MasterAdmin {@code product_integrations.inventory_read_scope}, and the link scope
 * beside it): {@code tms:tenants:list} and {@code tms:tenants:link} are the intended values. A route
 * whose scope is not configured is <b>closed</b>: it answers {@code 503 INVENTORY_NOT_CONFIGURED} /
 * {@code 503 TENANT_LINK_NOT_CONFIGURED} to every caller, like the M2M switch does when it is off.
 *
 * <p>A configured value is refused - read as not configured, and logged at start-up - when it is not a
 * single scope token, when it is one of the fixed scopes of {@link PlatformScopes} (a provisioning,
 * entitlements or lifecycle credential must never list or adopt tenants by accident), or when both
 * routes are given the same one: listing and adopting are separate grants.
 *
 * @param readScope scope of {@code GET /tenants}; {@code TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE}
 *                  (or the suite-wide name {@code EBIM_MASTERADMIN_M2M_INVENTORY_READ_SCOPE})
 * @param linkScope scope of {@code PUT /tenants/{id}/link}; {@code TMS_PLATFORM_M2M_TENANT_LINK_SCOPE}
 *                  (or {@code EBIM_MASTERADMIN_M2M_TENANT_LINK_SCOPE})
 */
@ConfigurationProperties(prefix = "tms.platform-inventory")
public record PlatformInventoryProperties(String readScope, String linkScope) {

    /** Scopes fixed by the TMS contract; neither inventory scope may reuse one of them. */
    private static final Set<String> RESERVED = Set.of(
            PlatformScopes.TENANT_CREATE, PlatformScopes.TENANT_READ,
            PlatformScopes.ENTITLEMENTS_WRITE, PlatformScopes.ENTITLEMENTS_READ,
            PlatformScopes.LIFECYCLE_WRITE, PlatformScopes.LIFECYCLE_READ);

    public PlatformInventoryProperties {
        readScope = readScope == null ? "" : readScope.trim();
        linkScope = linkScope == null ? "" : linkScope.trim();
    }

    /** Neither route configured. */
    public static PlatformInventoryProperties unset() {
        return new PlatformInventoryProperties(null, null);
    }

    /** The scope {@code GET /tenants} requires, or empty when that route is closed. */
    public Optional<String> listScope() {
        return usable(readScope, linkScope);
    }

    /** The scope {@code PUT /tenants/{id}/link} requires, or empty when that route is closed. */
    public Optional<String> adoptScope() {
        return usable(linkScope, readScope);
    }

    /** Why a configured value is not used; empty when everything configured is usable. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        describe("TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE", readScope, linkScope, problems);
        describe("TMS_PLATFORM_M2M_TENANT_LINK_SCOPE", linkScope, readScope, problems);
        return problems;
    }

    private static Optional<String> usable(String scope, String other) {
        return scope.isEmpty() || problem(scope, other) != null ? Optional.empty() : Optional.of(scope);
    }

    private static void describe(String variable, String scope, String other, List<String> problems) {
        if (scope.isEmpty()) {
            return;
        }
        String problem = problem(scope, other);
        if (problem != null) {
            problems.add(variable + " " + problem + "; the route stays closed (503).");
        }
    }

    private static String problem(String scope, String other) {
        if (scope.chars().anyMatch(Character::isWhitespace)) {
            return "must be a single scope";
        }
        if (RESERVED.contains(scope)) {
            return "reuses a fixed TMS scope (" + scope + ")";
        }
        if (scope.equals(other)) {
            return "is the same scope as the other inventory route";
        }
        return null;
    }
}
