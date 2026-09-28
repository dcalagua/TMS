package com.ebim.tms.iam.provisioning.api;

/**
 * The URL space MasterAdmin calls.
 *
 * <p>Outside {@code tms.api.base-path} for the same reason {@code /integration/v1} is: it is called
 * by another system on its own release schedule, and owning a whole prefix is what lets one security
 * chain claim it with a single matcher. {@code /internal} says what it is - not a browser API and not
 * a partner API - and the path is never listed in CORS.
 */
public final class PlatformProvisioningPaths {

    public static final String BASE = "/internal/platform-provisioning";
    public static final String ALL = BASE + "/**";
    public static final String HEALTH = BASE + "/health";
    public static final String TENANTS = BASE + "/tenants";
    public static final String TENANT = BASE + "/tenants/*";
    /** Commercial entitlements (ebim.entitlements/v1, V52): additive, with scopes of their own. */
    public static final String TENANT_ENTITLEMENTS = BASE + "/tenants/*/entitlements";
    public static final String ENTITLEMENTS_MANIFEST = BASE + "/entitlements/manifest";

    /** Whether a request URI belongs to the entitlements contract, whose errors are {@code {error, message}}. */
    public static boolean isEntitlements(String requestUri) {
        return requestUri != null && (requestUri.equals(ENTITLEMENTS_MANIFEST)
                || (requestUri.startsWith(TENANTS + "/") && requestUri.endsWith("/entitlements")));
    }

    private PlatformProvisioningPaths() {}
}
