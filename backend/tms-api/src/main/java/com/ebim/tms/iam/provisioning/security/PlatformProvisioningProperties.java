package com.ebim.tms.iam.provisioning.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the EBIM MasterAdmin provisioning surface, under {@code tms.platform-provisioning}.
 *
 * <p><b>Only the PUBLIC key lives here.</b> MasterAdmin holds the private key; there is no property
 * that could take one, and {@link #problems()} refuses a value that looks like one. An asymmetric
 * signature lets TMS verify who issued a token without being able to issue one - with a shared HMAC
 * secret, anybody able to read TMS's configuration could mint a provisioning token and create a
 * tenant.
 *
 * <p><b>Off by default.</b> An endpoint that creates tenants must not be switched on by the mere act
 * of deploying. With {@code enabled=false} the security chain is still registered and refuses every
 * call (the health probe answers {@code 503}); see {@link PlatformProvisioningSecurityConfig}.
 *
 * <p>Scopes are not configurable on purpose: they are part of the TMS contract
 * ({@link PlatformScopes}) and are written into the {@code @PreAuthorize} expressions.
 *
 * @param enabled           the switch; {@code TMS_PLATFORM_M2M_ENABLED}
 * @param issuer            exact {@code iss} required; {@code masteradmin.ebim} by default
 * @param audience          {@code aud} that must be present; {@code tms.ebim} by default
 * @param subject           exact {@code sub} required; {@code masteradmin-provisioning} by default
 * @param publicKey         PEM {@code -----BEGIN PUBLIC KEY-----} (P-256); from the deployment's
 *                          secret store, never committed, no default
 * @param maxTokenLifetime  upper bound for {@code exp - iat}; 300 s by default and never more
 * @param clockSkew         tolerance for {@code exp}, {@code nbf} and a future {@code iat}
 */
@ConfigurationProperties(prefix = "tms.platform-provisioning")
public record PlatformProvisioningProperties(
        boolean enabled,
        String issuer,
        String audience,
        String subject,
        String publicKey,
        Duration maxTokenLifetime,
        Duration clockSkew) {

    public static final String DEFAULT_ISSUER = "masteradmin.ebim";
    public static final String DEFAULT_AUDIENCE = "tms.ebim";
    public static final String DEFAULT_SUBJECT = "masteradmin-provisioning";

    /** The suite-wide ceiling (MasterAdmin M2M standard). A longer-lived token is refused outright. */
    public static final Duration MAX_TOKEN_LIFETIME_CEILING = Duration.ofSeconds(300);
    public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);

    public PlatformProvisioningProperties {
        issuer = blankToDefault(issuer, DEFAULT_ISSUER);
        audience = blankToDefault(audience, DEFAULT_AUDIENCE);
        subject = blankToDefault(subject, DEFAULT_SUBJECT);
        maxTokenLifetime = maxTokenLifetime != null ? maxTokenLifetime : MAX_TOKEN_LIFETIME_CEILING;
        clockSkew = clockSkew != null ? clockSkew : Duration.ofSeconds(30);
    }

    /** A disabled configuration, for code paths and tests that never turn the surface on. */
    public static PlatformProvisioningProperties disabled() {
        return new PlatformProvisioningProperties(false, null, null, null, null, null, null);
    }

    /**
     * Everything that prevents this configuration from being used, or an empty list.
     *
     * <p>Returns the whole list rather than the first problem, so one failed start names every
     * variable that is missing instead of one per deployment attempt. Pure: it is unit-tested
     * without a Spring context.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        if (isBlank(publicKey)) {
            problems.add("TMS_PLATFORM_M2M_PUBLIC_KEY is empty. Without MasterAdmin's public key no "
                    + "signature can be verified, and there is no degraded mode: either configure it "
                    + "or leave TMS_PLATFORM_M2M_ENABLED=false.");
        } else if (publicKey.toUpperCase(Locale.ROOT).contains("PRIVATE KEY")) {
            problems.add("TMS_PLATFORM_M2M_PUBLIC_KEY contains a PRIVATE key. TMS only verifies; a "
                    + "private key here would let this backend issue its own provisioning tokens.");
        }
        if (maxTokenLifetime.isZero() || maxTokenLifetime.isNegative()
                || maxTokenLifetime.compareTo(MAX_TOKEN_LIFETIME_CEILING) > 0) {
            problems.add("tms.platform-provisioning.max-token-lifetime must be between 1 s and 300 s, but was "
                    + maxTokenLifetime);
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
            problems.add("tms.platform-provisioning.clock-skew must be between 0 and 60 s, but was " + clockSkew);
        }
        return problems;
    }

    private static String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
