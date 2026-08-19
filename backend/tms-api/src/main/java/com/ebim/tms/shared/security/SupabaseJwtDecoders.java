package com.ebim.tms.shared.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the resource-server JWT decoder from configuration, and refuses to build a weak one.
 *
 * <p>Kept as a plain class rather than a {@code @Configuration} method body so the rules below
 * are unit-testable without a Spring context - a security decision that is only exercised by
 * booting an application is a security decision nobody tests.
 *
 * <p>Rules enforced here:
 *
 * <ul>
 *   <li>issuer and JWKS URI are mandatory in every profile - there is no "just start it
 *       without authentication" mode to forget about in production;</li>
 *   <li>in production both must be {@code https} and must not point at a loopback address, so
 *       a development profile that leaked into a deployment fails at startup instead of
 *       trusting a local key server;</li>
 *   <li>signatures are verified against the published JWKS with asymmetric algorithms only;
 *       there is no configuration path that accepts a shared secret;</li>
 *   <li>expiry (with a bounded skew), issuer, audience and the presence of a subject are all
 *       validated - a token that merely has a valid signature is not accepted.</li>
 * </ul>
 */
public final class SupabaseJwtDecoders {

    /** Supabase signs with an asymmetric key; symmetric algorithms are intentionally absent. */
    private static final Set<SignatureAlgorithm> ACCEPTED_ALGORITHMS =
            Set.of(SignatureAlgorithm.RS256, SignatureAlgorithm.ES256);

    private SupabaseJwtDecoders() {}

    public static NimbusJwtDecoder create(TmsSecurityProperties.Jwt jwt, boolean production) {
        validate(jwt, production);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.jwkSetUri())
                .jwsAlgorithms(algorithms -> algorithms.addAll(ACCEPTED_ALGORITHMS))
                .build();
        decoder.setJwtValidator(validator(jwt));
        return decoder;
    }

    /** The claim rules, shared by the runtime decoder and by the tests' locally signed tokens. */
    public static OAuth2TokenValidator<Jwt> validator(TmsSecurityProperties.Jwt jwt) {
        return validator(jwt.issuerUri(), jwt.audiences(), jwt.clockSkew());
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuerUri, List<String> audiences, Duration clockSkew) {
        Set<String> accepted = Set.copyOf(audiences);
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(clockSkew),
                new JwtIssuerValidator(issuerUri),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.stream().anyMatch(accepted::contains)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB,
                        subject -> subject != null && !subject.isBlank()));
    }

    static void validate(TmsSecurityProperties.Jwt jwt, boolean production) {
        if (!jwt.isConfigured()) {
            throw new IllegalStateException("""
                    TMS cannot start without Supabase JWT verification settings.
                    Set both of these (see backend/tms-api/.env.example):
                      TMS_SUPABASE_JWT_ISSUER_URI  -> tms.security.jwt.issuer-uri
                      TMS_SUPABASE_JWKS_URI        -> tms.security.jwt.jwk-set-uri
                    There is no fallback and no signing secret: starting with authentication \
                    disabled is not a supported mode.""");
        }

        requireUri(jwt.issuerUri(), "tms.security.jwt.issuer-uri", production);
        requireUri(jwt.jwkSetUri(), "tms.security.jwt.jwk-set-uri", production);

        if (jwt.clockSkew().isNegative() || jwt.clockSkew().compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException(
                    "tms.security.jwt.clock-skew must be between 0 and 5 minutes, but was " + jwt.clockSkew());
        }
    }

    private static void requireUri(String value, String property, boolean production) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException(property + " is not a valid URI: " + value);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalStateException(property + " must be an absolute URI, but was: " + value);
        }
        if (!production) {
            return;
        }
        if (!"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(property + " must use https in production, but was: " + value);
        }
        if (isLoopback(uri.getHost())) {
            throw new IllegalStateException(property
                    + " points at a loopback address in production: " + value
                    + ". This is what a development profile looks like when it reaches a deployment.");
        }
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || normalized.startsWith("127.") || "[::1]".equals(normalized)
                || "::1".equals(normalized);
    }
}
