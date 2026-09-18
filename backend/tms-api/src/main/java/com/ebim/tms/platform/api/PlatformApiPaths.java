package com.ebim.tms.platform.api;

/**
 * Where the control plane talks to this product.
 *
 * <p>{@code /internal/} because this is not a partner API: it is one EBIM system talking to another
 * about tenancy, and it should be reachable from the control plane and from nowhere a customer's
 * integration would ever point. The prefix is also what
 * {@code PlatformSecurityConfig.PLATFORM_PATH} claims, which is what keeps a human session and a
 * partner credential out of it.
 */
public final class PlatformApiPaths {

    public static final String V1 = "/internal/platform/v1";

    private PlatformApiPaths() {
    }
}
