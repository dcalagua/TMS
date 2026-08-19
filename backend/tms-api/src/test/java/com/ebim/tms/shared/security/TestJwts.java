package com.ebim.tms.shared.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints Supabase-shaped access tokens for tests, signed with a keypair generated in this JVM.
 *
 * <p>No test may contact a live authentication service, and no signing key may live in the
 * repository. Generating a throwaway RSA keypair per test run satisfies both: the tokens are
 * genuinely signed and genuinely verified, but the key exists only in memory and only for the
 * duration of the run.
 *
 * <p>Crucially, the decoder built here uses {@link SupabaseJwtDecoders#validator} - the same
 * expiry, issuer, audience and subject rules the running application applies. Only the key
 * source differs, so a test that passes here exercises the production validation logic rather
 * than a relaxed copy of it.
 */
public final class TestJwts {

    /** Matches {@code tms.security.jwt.issuer-uri} in {@code application-test.yml}. */
    public static final String ISSUER = "https://tms-test.invalid/auth/v1";

    public static final String AUDIENCE = "authenticated";

    private static final KeyPair KEY_PAIR = generateKeyPair();

    /** A second keypair, never published to the decoder: the "forged signature" case. */
    private static final KeyPair FOREIGN_KEY_PAIR = generateKeyPair();

    private TestJwts() {}

    /** A decoder that trusts only {@link #KEY_PAIR} and applies the production claim rules. */
    public static JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(
                SupabaseJwtDecoders.validator(ISSUER, List.of(AUDIENCE), Duration.ofSeconds(30)));
        return decoder;
    }

    /** A valid token for a Supabase auth user id. */
    public static String validFor(UUID authUserId) {
        return sign(KEY_PAIR, claims(authUserId.toString(), ISSUER, AUDIENCE,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600)));
    }

    /** Correctly shaped, correctly signed, but expired well beyond the accepted clock skew. */
    public static String expiredFor(UUID authUserId) {
        return sign(KEY_PAIR, claims(authUserId.toString(), ISSUER, AUDIENCE,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
    }

    /** Signed by a key the decoder does not trust: a forgery. */
    public static String forgedFor(UUID authUserId) {
        return sign(FOREIGN_KEY_PAIR, claims(authUserId.toString(), ISSUER, AUDIENCE,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600)));
    }

    /** Correctly signed by this key, but issued by another Supabase project. */
    public static String wrongIssuerFor(UUID authUserId) {
        return sign(KEY_PAIR, claims(authUserId.toString(), "https://another-project.invalid/auth/v1",
                AUDIENCE, Instant.now().minusSeconds(60), Instant.now().plusSeconds(600)));
    }

    /** Correctly signed and issued, but minted for a different audience. */
    public static String wrongAudienceFor(UUID authUserId) {
        return sign(KEY_PAIR, claims(authUserId.toString(), ISSUER, "some-other-service",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600)));
    }

    /** A subject that is not a Supabase user id, so it cannot map to {@code auth_user_id}. */
    public static String nonUuidSubject() {
        return sign(KEY_PAIR, claims("not-a-uuid", ISSUER, AUDIENCE,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600)));
    }

    private static JWTClaimsSet claims(String subject, String issuer, String audience,
            Instant issuedAt, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                // Supabase puts the authenticated role here. TMS ignores it on purpose: roles
                // and permissions come from tms.membership, never from a claim.
                .claim("role", "authenticated")
                .claim("email", "someone@example.invalid")
                .build();
    }

    private static String sign(KeyPair keyPair, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (Exception signingFailed) {
            throw new IllegalStateException("could not sign a test token", signingFailed);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception unavailable) {
            throw new IllegalStateException("RSA is not available in this JVM", unavailable);
        }
    }
}
