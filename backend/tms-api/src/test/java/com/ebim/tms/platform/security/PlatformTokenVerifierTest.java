package com.ebim.tms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ebim.tms.platform.security.PlatformTokenVerifier.VerifiedToken;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The machine-token matrix: every way a control-plane token can be wrong, and the code TMS answers
 * with.
 *
 * <p>Every key here is generated for the test and thrown away. The QAS public key is never read by a
 * test, and no private key exists anywhere in this repository - the one MasterAdmin signs with lives
 * only on MasterAdmin.
 *
 * <p>The clock is fixed. A token matrix whose results depend on how long the suite took to reach it
 * is a matrix that will fail on a slow machine and be re-run until it passes, which is the same as
 * having no matrix at all.
 */
class PlatformTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ISSUER = "masteradmin.ebim";
    private static final String AUDIENCE = "tms.ebim";
    private static final String CREATE_SCOPE = "tms:tenant:create";

    private static KeyPair controlPlaneKey;
    private static KeyPair otherKey;
    private static KeyPair rsaKey;

    @BeforeAll
    static void keys() throws Exception {
        controlPlaneKey = ecKeyPair();
        otherKey = ecKeyPair();
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        rsaKey = rsa.generateKeyPair();
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    @Nested
    @DisplayName("a token TMS accepts")
    class Accepted {

        @Test
        @DisplayName("carries the claims the provisioning record needs, and the scopes it was granted")
        void valid() throws Exception {
            VerifiedToken token = verifier(properties()).verify(signed(claims().build()));

            assertThat(token.subject()).isEqualTo("masteradmin-service");
            assertThat(token.jti()).isEqualTo("jti-1");
            assertThat(token.scopes()).containsExactly(CREATE_SCOPE);
        }

        @Test
        @DisplayName("carries actor_id and actor_role forward for audit, and they grant nothing")
        void actorClaimsAreAuditOnly() throws Exception {
            VerifiedToken token = verifier(properties()).verify(signed(claims()
                    .claim("actor_id", "u-42")
                    .claim("actor_role", "PLATFORM_OWNER")
                    .build()));

            assertThat(token.actorId()).isEqualTo("u-42");
            assertThat(token.actorRole()).isEqualTo("PLATFORM_OWNER");
            // The role claims a name this product has never heard of, and it changes nothing: the
            // authorities come from the scope and only from the scope.
            assertThat(token.scopes()).containsExactly(CREATE_SCOPE);
        }

        @Test
        @DisplayName("accepts a scope claim sent as a list, because control planes differ")
        void scopeAsList() throws Exception {
            VerifiedToken token = verifier(properties()).verify(signed(claims()
                    .claim("scope", List.of(CREATE_SCOPE, "tms:tenant:read"))
                    .build()));

            assertThat(token.scopes()).containsExactly(CREATE_SCOPE, "tms:tenant:read");
        }
    }

    @Nested
    @DisplayName("a token TMS refuses")
    class Refused {

        @Test
        @DisplayName("expired")
        void expired() throws Exception {
            JWTClaimsSet expired = claims()
                    .issueTime(Date.from(NOW.minus(Duration.ofMinutes(10))))
                    .expirationTime(Date.from(NOW.minus(Duration.ofMinutes(5))))
                    .build();

            assertRefused(signed(expired), ProblemType.INVALID_M2M_TOKEN, "expired");
        }

        @Test
        @DisplayName("issued by somebody else")
        void wrongIssuer() throws Exception {
            assertRefused(signed(claims().issuer("someone.else").build()), ProblemType.INVALID_ISSUER);
        }

        @Test
        @DisplayName("minted for another product")
        void wrongAudience() throws Exception {
            assertRefused(signed(claims().audience("ewm.ebim").build()), ProblemType.INVALID_AUDIENCE);
        }

        @Test
        @DisplayName("signed with HS256, where the public key would become a shared secret")
        void hs256() throws Exception {
            byte[] secret = "a-32-byte-secret-for-hs256-test!".getBytes(StandardCharsets.UTF_8);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
            jwt.sign(new MACSigner(secret));

            assertRefused(jwt.serialize(), ProblemType.INVALID_M2M_TOKEN, "ES256");
        }

        @Test
        @DisplayName("unsecured: alg=none")
        void algNone() {
            String header = base64Url("{\"alg\":\"none\"}");
            String payload = base64Url("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"x\"}");

            assertRefused(header + "." + payload + ".", ProblemType.INVALID_M2M_TOKEN);
        }

        @Test
        @DisplayName("signed with RS256 by a key TMS was never given - the shape a human Supabase token has")
        void humanShapedToken() throws Exception {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims()
                    .issuer("https://project.supabase.co/auth/v1")
                    .audience("authenticated")
                    .build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) rsaKey.getPrivate()));

            assertRefused(jwt.serialize(), ProblemType.INVALID_M2M_TOKEN, "ES256");
        }

        @Test
        @DisplayName("signed with a different EC key")
        void wrongKey() throws Exception {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims().build());
            jwt.sign(new ECDSASigner((ECPrivateKey) otherKey.getPrivate()));

            assertRefused(jwt.serialize(), ProblemType.INVALID_M2M_TOKEN, "signature");
        }

        @Test
        @DisplayName("no jti, so the call could not be audited")
        void missingJti() throws Exception {
            assertRefused(signed(claims().jwtID(null).build()), ProblemType.INVALID_M2M_TOKEN, "jti");
        }

        @Test
        @DisplayName("no sub")
        void missingSubject() throws Exception {
            assertRefused(signed(claims().subject(null).build()), ProblemType.INVALID_M2M_TOKEN, "sub");
        }

        @Test
        @DisplayName("no scope at all")
        void missingScope() throws Exception {
            assertRefused(signed(claims().claim("scope", null).build()), ProblemType.INVALID_M2M_TOKEN, "scope");
        }

        @Test
        @DisplayName("valid for longer than a single call needs")
        void lifetimeTooLong() throws Exception {
            JWTClaimsSet longLived = claims()
                    .issueTime(Date.from(NOW))
                    .expirationTime(Date.from(NOW.plus(Duration.ofHours(1))))
                    .build();

            assertRefused(signed(longLived), ProblemType.INVALID_M2M_TOKEN, "lifetime");
        }

        @Test
        @DisplayName("not a token at all - an opaque credential, as the legacy integration API uses")
        void opaqueCredential() {
            assertRefused("dGVzdC1jbGllbnQ6dGVzdC1zZWNyZXQ=", ProblemType.INVALID_M2M_TOKEN);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a deployment with the feature off accepts nothing, and says so")
        void disabled() throws Exception {
            PlatformM2mProperties off = new PlatformM2mProperties(false, ISSUER, AUDIENCE, "ES256",
                    Duration.ofSeconds(300), Duration.ofSeconds(30), CREATE_SCOPE, "tms:tenant:read",
                    publicKeyBase64());

            assertRefused(signed(claims().build()), off, ProblemType.FEATURE_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("a private key configured where the public one belongs is refused, not used")
        void privateKeyRefused() throws Exception {
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(controlPlaneKey.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            PlatformM2mProperties wrong = new PlatformM2mProperties(true, ISSUER, AUDIENCE, "ES256",
                    Duration.ofSeconds(300), Duration.ofSeconds(30), CREATE_SCOPE, "tms:tenant:read",
                    Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII)));

            // It would have worked - an EC private key encodes its public half - which is exactly
            // why this has to be refused rather than quietly accepted.
            assertRefused(signed(claims().build()), wrong, ProblemType.FEATURE_NOT_CONFIGURED);
        }
    }

    // ---------------------------------------------------------------------------------------

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("masteradmin-service")
                .jwtID("jti-1")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(Duration.ofSeconds(120))))
                .claim("scope", CREATE_SCOPE);
    }

    private static String signed(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims);
        jwt.sign(new ECDSASigner((ECPrivateKey) controlPlaneKey.getPrivate()));
        return jwt.serialize();
    }

    private static PlatformM2mProperties properties() {
        return new PlatformM2mProperties(true, ISSUER, AUDIENCE, "ES256", Duration.ofSeconds(300),
                Duration.ofSeconds(30), CREATE_SCOPE, "tms:tenant:read", publicKeyBase64());
    }

    private static String publicKeyBase64() {
        ECPublicKey publicKey = (ECPublicKey) controlPlaneKey.getPublic();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }

    private static PlatformTokenVerifier verifier(PlatformM2mProperties properties) {
        return new PlatformTokenVerifier(properties, CLOCK);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRefused(String token, ProblemType expected) {
        assertRefused(token, properties(), expected);
    }

    private static void assertRefused(String token, ProblemType expected, String mentioning) {
        ProblemTypedException refusal = catchThrowableOfType(ProblemTypedException.class,
                () -> verifier(properties()).verify(token));
        assertThat(refusal).isNotNull();
        assertThat(refusal.type()).isEqualTo(expected);
        assertThat(refusal.getMessage()).containsIgnoringCase(mentioning);
    }

    private static void assertRefused(String token, PlatformM2mProperties properties, ProblemType expected) {
        assertThatThrownBy(() -> verifier(properties).verify(token))
                .isInstanceOf(ProblemTypedException.class)
                .extracting(failure -> ((ProblemTypedException) failure).type())
                .isEqualTo(expected);
    }
}
