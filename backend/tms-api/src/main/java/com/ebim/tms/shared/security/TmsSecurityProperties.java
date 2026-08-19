package com.ebim.tms.shared.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed security settings under the {@code tms.security} prefix.
 *
 * <p>Every value comes from configuration, and the JWT values have no usable default: there
 * is no signing secret in this repository and none can be introduced, because TMS only ever
 * verifies Supabase's signature against the published JWKS.
 *
 * @param jwt  Supabase resource-server coordinates
 * @param cors browser origins allowed to call the API
 */
@ConfigurationProperties(prefix = "tms.security")
public record TmsSecurityProperties(Jwt jwt, Cors cors) {

    public TmsSecurityProperties {
        jwt = jwt != null ? jwt : new Jwt(null, null, null, null);
        cors = cors != null ? cors : new Cors(null);
    }

    /**
     * @param issuerUri  the {@code iss} every accepted token must carry, for example
     *     {@code https://<project-ref>.supabase.co/auth/v1}
     * @param jwkSetUri  where the public signing keys are published; the backend fetches and
     *     caches them, so key rotation needs no redeployment
     * @param audiences  accepted {@code aud} values; Supabase issues {@code authenticated}
     * @param clockSkew  tolerance for {@code exp}/{@code nbf} against clock drift
     */
    public record Jwt(String issuerUri, String jwkSetUri, List<String> audiences, Duration clockSkew) {

        public static final List<String> DEFAULT_AUDIENCES = List.of("authenticated");
        public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

        public Jwt {
            audiences = audiences == null || audiences.isEmpty() ? DEFAULT_AUDIENCES : List.copyOf(audiences);
            clockSkew = clockSkew != null ? clockSkew : DEFAULT_CLOCK_SKEW;
        }

        public boolean isConfigured() {
            return hasText(issuerUri) && hasText(jwkSetUri);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * @param allowedOrigins exact browser origins allowed to call the API. Empty - the default
     *     - means no cross-origin request is allowed at all. Wildcards are rejected at startup.
     */
    public record Cors(List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
