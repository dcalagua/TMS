package com.ebim.tms.platform.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordinates for verifying an EBIM MasterAdmin machine token.
 *
 * <p>The property names follow the project's own namespace ({@code tms.<area>.<name>}); the
 * environment variables the brief specifies - {@code EBIM_MASTERADMIN_M2M_*} - are wired to them in
 * {@code application.yml}, which is where every other setting in this product reads its environment
 * too. The mapping is written out in {@code docs/platform-provisioning/SECURITY.md} so an operator
 * setting QAS never has to read Java to find the name of a variable.
 *
 * @param enabled off by default. A product that has not been told the control plane's public key
 *     must not appear to accept provisioning calls
 * @param publicKeyBase64 the PEM, base64-encoded. Base64 because a PEM is multi-line and
 *     environment variables are not, and because it keeps the key out of the repository: there is
 *     no file path setting on purpose, so no deployment can end up reading a key from a developer's
 *     disk
 * @param maxTokenLifetime the largest {@code exp - iat} accepted. A control-plane token is minted
 *     for one call; one that is valid for a day is a credential, and a credential that leaks is a
 *     different kind of problem
 */
@ConfigurationProperties(prefix = "tms.platform.m2m")
public record PlatformM2mProperties(
        boolean enabled,
        String issuer,
        String audience,
        String algorithm,
        Duration maxTokenLifetime,
        Duration clockSkew,
        String createScope,
        String readScope,
        String publicKeyBase64) {

    public PlatformM2mProperties {
        maxTokenLifetime = maxTokenLifetime == null ? Duration.ofSeconds(300) : maxTokenLifetime;
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
        algorithm = algorithm == null || algorithm.isBlank() ? "ES256" : algorithm.trim();
        issuer = issuer == null ? "" : issuer.trim();
        audience = audience == null ? "" : audience.trim();
        createScope = createScope == null || createScope.isBlank() ? "tms:tenant:create" : createScope.trim();
        readScope = readScope == null || readScope.isBlank() ? "tms:tenant:read" : readScope.trim();
    }

    /** Configured well enough to verify anything at all. */
    public boolean usable() {
        return enabled && !issuer.isBlank() && !audience.isBlank()
                && publicKeyBase64 != null && !publicKeyBase64.isBlank();
    }
}
