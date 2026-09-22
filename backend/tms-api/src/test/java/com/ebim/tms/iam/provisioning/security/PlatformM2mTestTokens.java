package com.ebim.tms.iam.provisioning.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Mints MasterAdmin-shaped M2M tokens for tests, signed with a P-256 key generated in this JVM.
 *
 * <p>No key lives in the repository and none leaves the test run. {@link #publicKeyPem()} is what a
 * test configures as {@code tms.platform-provisioning.public-key}, exactly as a deployment would.
 */
public final class PlatformM2mTestTokens {

    public static final String ISSUER = PlatformProvisioningProperties.DEFAULT_ISSUER;
    public static final String AUDIENCE = PlatformProvisioningProperties.DEFAULT_AUDIENCE;
    public static final String SUBJECT = PlatformProvisioningProperties.DEFAULT_SUBJECT;

    private static final KeyPair KEY_PAIR = ecKeyPair();
    /** Never configured anywhere: tokens it signs are forgeries. */
    private static final KeyPair FOREIGN_KEY_PAIR = ecKeyPair();

    private PlatformM2mTestTokens() {}

    public static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEY_PAIR.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    public static PlatformProvisioningProperties enabledProperties() {
        return new PlatformProvisioningProperties(true, null, null, null, publicKeyPem(), null, null);
    }

    /** The claims MasterAdmin sends: 120 s of life, one scope. */
    public static JWTClaimsSet.Builder claims(String scope) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(SUBJECT)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(115)))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", scope)
                .claim("actor_id", "10000000-0000-4000-a000-000000000002")
                .claim("actor_role", "TECH_LEAD")
                .claim("correlation_id", UUID.randomUUID().toString());
    }

    public static String valid(String scope) {
        return es256(claims(scope).build());
    }

    public static String with(String scope, UnaryOperator<JWTClaimsSet.Builder> change) {
        return es256(change.apply(claims(scope)).build());
    }

    public static String es256(JWTClaimsSet claims) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID("masteradmin-qas").build(),
                claims, KEY_PAIR);
    }

    public static String forged(String scope) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(),
                claims(scope).build(), FOREIGN_KEY_PAIR);
    }

    /** HS256 with a shared secret: the algorithm-confusion attempt. */
    public static String hs256(String scope) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims(scope).build());
            jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef".getBytes()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A correctly-formed RS256 token from a key TMS does not know: ES256 is the only algorithm. */
    public static String rs256(String scope) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair rsa = generator.generateKeyPair();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims(scope).build());
            jwt.sign(new RSASSASigner(rsa.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code alg: none}. */
    public static String unsigned(String scope) {
        return new PlainJWT(claims(scope).build()).serialize();
    }

    private static String sign(JWSHeader header, JWTClaimsSet claims, KeyPair keyPair) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner((ECPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("could not sign a test token", failure);
        }
    }

    static KeyPair ecKeyPair() {
        return keyPair("secp256r1");
    }

    static KeyPair keyPair(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (Exception unavailable) {
            throw new IllegalStateException("EC is not available in this JVM", unavailable);
        }
    }
}
