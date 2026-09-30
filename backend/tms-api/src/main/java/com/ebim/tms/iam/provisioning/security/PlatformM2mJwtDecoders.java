package com.ebim.tms.iam.provisioning.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the decoder for MasterAdmin's machine tokens, and refuses to build a weak one.
 *
 * <p>A plain class, like {@code SupabaseJwtDecoders}, so every rule is unit-tested by signing tokens
 * with a throwaway key, without a Spring context. It is stricter than the user-facing decoder in
 * four ways:
 *
 * <ul>
 *   <li><b>One algorithm, pinned: ES256.</b> The header's {@code alg} selects nothing. The key
 *       selector hands out the key only when the header says exactly ES256, so {@code alg: none} and
 *       the classic HS256-signed-with-the-public-key forgery never reach signature evaluation.</li>
 *   <li><b>One key, from configuration.</b> No JWKS: TMS would otherwise have to reach MasterAdmin
 *       over the network at start-up and fail in a new way when it cannot. {@code kid} is ignored -
 *       there is no set to choose from, and a label must not decide whether a tenant is created.</li>
 *   <li><b>Short-lived only.</b> {@code iat} and {@code exp} are mandatory and
 *       {@code exp - iat} may not exceed the configured maximum (300 s at most). A year-long token
 *       found in a log would otherwise be a permanent credential nobody knows about.</li>
 *   <li><b>Accountable.</b> {@code jti} and {@code sub} are mandatory and {@code sub} must be the
 *       configured system subject. At least one scope must be present; which one is needed is
 *       decided per operation by the security chain.</li>
 * </ul>
 *
 * <p>{@code jti} is required and recorded, not enforced as single-use: MasterAdmin signs one token
 * per operation and reuses it for its own transport retries, so a one-use rule would turn a timeout
 * into a hard failure. Replays of the operation itself are answered by the Idempotency-Key.
 */
public final class PlatformM2mJwtDecoders {

    private PlatformM2mJwtDecoders() {}

    public static JwtDecoder create(PlatformProvisioningProperties properties) {
        return create(properties, Clock.systemUTC());
    }

    static JwtDecoder create(PlatformProvisioningProperties properties, Clock clock) {
        List<String> problems = properties.problems();
        if (!properties.enabled()) {
            problems = List.of("tms.platform-provisioning.enabled is false");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "MasterAdmin provisioning is enabled but its M2M configuration is not usable: "
                            + String.join(" | ", problems));
        }

        ECPublicKey key = parsePublicKey(properties.publicKey());
        JWSKeySelector<SecurityContext> selector = (header, context) ->
                JWSAlgorithm.ES256.equals(header.getAlgorithm()) ? List.of(key) : List.of();

        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(selector);
        // Nimbus would otherwise check expiry with its own default margin. Time is decided once, by
        // the validators below, with the declared skew.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    /** The claim rules; public so tests can apply them to tokens they build themselves. */
    public static OAuth2TokenValidator<Jwt> validator(PlatformProvisioningProperties properties, Clock clock) {
        Duration skew = properties.clockSkew();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(skew);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                audience(properties.audience()),
                exact("sub", Jwt::getSubject, properties.subject()),
                required("jti", Jwt::getId),
                lifetime(properties.maxTokenLifetime(), skew, clock),
                token -> PlatformScopes.of(token).isEmpty()
                        ? failure("the token declares no scope")
                        : OAuth2TokenValidatorResult.success());
    }

    /**
     * PEM SubjectPublicKeyInfo to an EC P-256 key. A secret manager's single-line form with literal
     * {@code \n} is accepted. The key material is never echoed in an error message.
     */
    static ECPublicKey parsePublicKey(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        ECPublicKey key;
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception notAnEcKey) {
            throw new IllegalStateException("TMS_PLATFORM_M2M_PUBLIC_KEY is not an EC public key in PEM "
                    + "(-----BEGIN PUBLIC KEY-----). ES256 requires a P-256 key.");
        }
        ECParameterSpec params = key.getParams();
        if (params == null || params.getCurve().getField().getFieldSize() != 256) {
            throw new IllegalStateException("TMS_PLATFORM_M2M_PUBLIC_KEY is an EC key but not on P-256; "
                    + "ES256 requires P-256.");
        }
        return key;
    }

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        return token -> token.getAudience() != null && token.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : failure("the token is not addressed to this application");
    }

    private static OAuth2TokenValidator<Jwt> exact(String claim, Function<Jwt, String> reader, String expected) {
        return token -> expected.equals(reader.apply(token))
                ? OAuth2TokenValidatorResult.success()
                : failure("unexpected `" + claim + "`");
    }

    private static OAuth2TokenValidator<Jwt> required(String claim, Function<Jwt, String> reader) {
        return token -> {
            String value = reader.apply(token);
            return value != null && !value.isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : failure("missing `" + claim + "`");
        };
    }

    /**
     * {@code iat} and {@code exp} both present, {@code iat} not in the future beyond the skew, and
     * {@code exp - iat} within the maximum. Without {@code iat} the lifetime cannot be measured, and
     * an issuer could dodge the ceiling simply by leaving it out.
     */
    private static OAuth2TokenValidator<Jwt> lifetime(Duration maximum, Duration skew, Clock clock) {
        return token -> {
            Instant issuedAt = token.getIssuedAt();
            Instant expiresAt = token.getExpiresAt();
            if (issuedAt == null || expiresAt == null) {
                return failure("`iat` and `exp` are required");
            }
            if (issuedAt.isAfter(Instant.now(clock).plus(skew))) {
                return failure("`iat` is in the future");
            }
            Duration lifetime = Duration.between(issuedAt, expiresAt);
            if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(maximum) > 0) {
                return failure("the token lives longer than the accepted maximum");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
