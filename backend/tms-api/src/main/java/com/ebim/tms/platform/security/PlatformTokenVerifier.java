package com.ebim.tms.platform.security;

import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Verifies a MasterAdmin machine token, and refuses it in a way the control plane can act on.
 *
 * <p>Written against Nimbus directly rather than through a {@code JwtDecoder}. The reason is the
 * error contract: a decoder collapses every claim failure into one "invalid token", and the brief -
 * rightly - asks for {@code INVALID_ISSUER} and {@code INVALID_AUDIENCE} to be distinguishable from
 * a bad signature. An integrator who sent a token to the wrong product should be told that, not left
 * to guess.
 *
 * <p>What it refuses, and why each one is checked rather than assumed:
 *
 * <ul>
 *   <li><strong>alg</strong> must be exactly ES256. This is the check that rejects {@code HS256} -
 *       where the "public" key would become a shared secret an attacker already has - and
 *       {@code alg=none}, which {@link SignedJWT#parse} refuses outright because an unsecured JWT is
 *       not a JWS.</li>
 *   <li><strong>signature</strong> against the configured public key, and only that key. There is no
 *       JWKS fetch: a control plane that could rotate keys by changing a URL could also be made to
 *       point somewhere else.</li>
 *   <li><strong>iss</strong>, <strong>aud</strong> - a valid signature from the right key still says
 *       nothing about who the token was meant for.</li>
 *   <li><strong>sub</strong>, <strong>jti</strong> - both are recorded on the provisioning row, so a
 *       token that carries neither cannot be audited and is refused.</li>
 *   <li><strong>exp - iat</strong> within the configured maximum. A long-lived token is a
 *       credential; this API accepts tokens minted for a call.</li>
 *   <li><strong>scope</strong> must be present. Authorisation itself happens later, in method
 *       security, against the scope this turns into an authority.</li>
 * </ul>
 *
 * <p>{@code actor_id} and {@code actor_role} are read and carried for audit. They are never checked:
 * a control plane that lies about which of its humans pressed the button gains nothing here.
 */
@Component
public class PlatformTokenVerifier {

    private final PlatformM2mProperties properties;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicReference<ECPublicKey> key =
            new java.util.concurrent.atomic.AtomicReference<>();

    public PlatformTokenVerifier(PlatformM2mProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public VerifiedToken verify(String token) {
        if (!properties.usable()) {
            throw new ProblemTypedException(ProblemType.FEATURE_NOT_CONFIGURED,
                    "Platform provisioning is not configured on this deployment.");
        }

        SignedJWT jwt = parse(token);
        requireExpectedAlgorithm(jwt);
        requireValidSignature(jwt);

        JWTClaimsSet claims = claimsOf(jwt);
        requireIssuer(claims);
        requireAudience(claims);

        String subject = requireClaim(claims.getSubject(), "sub");
        String jti = requireClaim(claims.getJWTID(), "jti");
        requireLifetime(claims);

        Set<String> scopes = scopesOf(claims);
        if (scopes.isEmpty()) {
            throw invalid("The token carries no scope.");
        }

        return new VerifiedToken(subject, jti, scopes,
                stringClaim(claims, "actor_id"), stringClaim(claims, "actor_role"));
    }

    private SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (java.text.ParseException notAJws) {
            // An unsecured JWT (alg=none) lands here too: it is not a JWS, so it never reaches a
            // signature check that could be tricked into skipping.
            throw invalid("The token is not a signed JWT.");
        }
    }

    private void requireExpectedAlgorithm(SignedJWT jwt) {
        JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();
        if (algorithm == null || !algorithm.getName().equals(properties.algorithm())) {
            throw invalid("The token must be signed with " + properties.algorithm() + ".");
        }
    }

    private void requireValidSignature(SignedJWT jwt) {
        try {
            if (!jwt.verify(new ECDSAVerifier(publicKey()))) {
                throw invalid("The token signature does not verify against the configured key.");
            }
        } catch (com.nimbusds.jose.JOSEException cannotVerify) {
            throw invalid("The token signature could not be verified.");
        }
    }

    private JWTClaimsSet claimsOf(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (java.text.ParseException unreadable) {
            throw invalid("The token claims could not be read.");
        }
    }

    private void requireIssuer(JWTClaimsSet claims) {
        if (!properties.issuer().equals(claims.getIssuer())) {
            throw new ProblemTypedException(ProblemType.INVALID_ISSUER,
                    "The token was not issued by the configured control plane.");
        }
    }

    private void requireAudience(JWTClaimsSet claims) {
        List<String> audiences = claims.getAudience();
        if (audiences == null || !audiences.contains(properties.audience())) {
            throw new ProblemTypedException(ProblemType.INVALID_AUDIENCE,
                    "The token was not issued for this product.");
        }
    }

    private void requireLifetime(JWTClaimsSet claims) {
        Date issuedAt = claims.getIssueTime();
        Date expiresAt = claims.getExpirationTime();
        if (issuedAt == null || expiresAt == null) {
            throw invalid("The token must carry both iat and exp.");
        }
        Duration lifetime = Duration.between(issuedAt.toInstant(), expiresAt.toInstant());
        if (lifetime.isNegative() || lifetime.compareTo(properties.maxTokenLifetime()) > 0) {
            throw invalid("The token lifetime exceeds the maximum this API accepts ("
                    + properties.maxTokenLifetime().toSeconds() + "s).");
        }
        Instant now = clock.instant();
        Duration skew = properties.clockSkew();
        if (now.isAfter(expiresAt.toInstant().plus(skew))) {
            throw invalid("The token has expired.");
        }
        if (now.plus(skew).isBefore(issuedAt.toInstant())) {
            throw invalid("The token was issued in the future.");
        }
    }

    private static String requireClaim(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid("The token must carry a " + name + " claim.");
        }
        return value;
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }

    /** Space-delimited, as OAuth 2 writes it; a list is accepted too because control planes differ. */
    private static Set<String> scopesOf(JWTClaimsSet claims) {
        Object raw = claims.getClaim("scope");
        Set<String> scopes = new LinkedHashSet<>();
        if (raw instanceof String text) {
            for (String scope : text.trim().split("\\s+")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        } else if (raw instanceof List<?> list) {
            list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                    .filter(scope -> !scope.isBlank()).forEach(scopes::add);
        }
        return scopes;
    }

    /**
     * The configured verification key, parsed once.
     *
     * <p>Refuses outright if what was configured contains private material. TMS is only ever given
     * the public half, and a deployment that has somehow been handed a private key must fail loudly
     * rather than quietly work - it would work perfectly, which is exactly the problem.
     */
    private ECPublicKey publicKey() {
        ECPublicKey cached = key.get();
        if (cached != null) {
            return cached;
        }
        try {
            String pem = new String(Base64.getDecoder().decode(properties.publicKeyBase64().trim()),
                    java.nio.charset.StandardCharsets.US_ASCII);
            if (pem.contains("PRIVATE KEY")) {
                throw new IllegalStateException("a private key was configured where the public key belongs");
            }
            String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            java.security.PublicKey parsed = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(der));
            if (!(parsed instanceof ECPublicKey elliptic)) {
                throw new IllegalStateException("the configured key is not an EC public key");
            }
            key.compareAndSet(null, elliptic);
            return elliptic;
        } catch (RuntimeException | java.security.GeneralSecurityException unusable) {
            throw new ProblemTypedException(ProblemType.FEATURE_NOT_CONFIGURED,
                    "The configured control-plane public key could not be read.");
        }
    }

    /** The claims a verified token carries forward. */
    public record VerifiedToken(String subject, String jti, Set<String> scopes, String actorId, String actorRole) {
    }

    private static ProblemTypedException invalid(String detail) {
        return new ProblemTypedException(ProblemType.INVALID_M2M_TOKEN, detail);
    }
}
