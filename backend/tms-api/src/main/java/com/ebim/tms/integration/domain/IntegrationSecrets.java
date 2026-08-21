package com.ebim.tms.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Generation and verification of integration credentials.
 *
 * <h2>The scheme</h2>
 *
 * <p>A credential is a public {@code clientId} and a secret, presented together as
 * {@code Authorization: Bearer clientId.secret}. Both are produced here and only here:
 *
 * <ul>
 *   <li>{@code clientId} = {@code tmsc_} + base64url of 16 random bytes (128 bits);</li>
 *   <li>{@code secret} = {@code tmss_} + base64url of 32 random bytes (256 bits).</li>
 * </ul>
 *
 * <p>The prefixes are not decoration. They make a leaked value recognisable to a secret scanner
 * and to a human reading a support ticket, and they let the API reject a malformed credential
 * before it touches the database. The separator is a full stop, which is outside the base64url
 * alphabet, so splitting the token can never be ambiguous.
 *
 * <h2>Why SHA-256 and not bcrypt/argon2</h2>
 *
 * <p>A password KDF's work factor exists to slow a search of a low-entropy input. This input has
 * 256 bits of entropy from a CSPRNG and cannot be guessed, so a work factor buys nothing - while
 * costing tens of milliseconds on <em>every</em> inbound call, which on a high-volume order feed
 * is a self-inflicted denial of service. It is the same reasoning behind API-key systems in
 * general using a fast digest for machine tokens and a KDF for human passwords.
 *
 * <p>What does the work here: entropy at generation, a one-way digest at rest (a database dump
 * yields nothing usable), a constant-time comparison at verification, and TLS in transit. A
 * pepper was considered and rejected for the same reason as the KDF - it defends against
 * inverting a low-entropy hash, and there is no low-entropy hash to invert.
 *
 * <h2>What never happens</h2>
 *
 * <p>The secret is returned exactly once, by the call that creates or rotates it. It is never
 * stored, never logged, never included in an error message and never recoverable. A partner that
 * loses it rotates.
 */
public final class IntegrationSecrets {

    /** The scheme recorded in {@code integration_client.secret_algorithm}. */
    public static final String ALGORITHM = "sha256";

    public static final String CLIENT_ID_PREFIX = "tmsc_";
    public static final String SECRET_PREFIX = "tmss_";

    /** Separates the two halves of the bearer token; outside the base64url alphabet on purpose. */
    public static final char TOKEN_SEPARATOR = '.';

    static final int CLIENT_ID_BYTES = 16;
    static final int SECRET_BYTES = 32;

    private static final Pattern CLIENT_ID_SHAPE = Pattern.compile("^tmsc_[A-Za-z0-9_-]{22}$");
    private static final Pattern SECRET_SHAPE = Pattern.compile("^tmss_[A-Za-z0-9_-]{43}$");
    private static final Pattern HASH_SHAPE = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * One shared {@link SecureRandom}. It is thread-safe, it seeds itself from the platform
     * entropy source, and reusing it avoids the reseeding cost of a per-call instance.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private IntegrationSecrets() {}

    public static String newClientId() {
        return CLIENT_ID_PREFIX + randomToken(CLIENT_ID_BYTES);
    }

    public static String newSecret() {
        return SECRET_PREFIX + randomToken(SECRET_BYTES);
    }

    /** Lower-case hex SHA-256, the shape {@code ck_integration_client_secret_hash_shape} requires. */
    public static String hash(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("secret must not be null");
        }
        return HexFormat.of().formatHex(digest(secret));
    }

    /**
     * Whether a presented secret matches a stored hash, compared in constant time.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}: the JDK's version does
     * not return early on the first differing byte, so the comparison time carries no information
     * about how much of a guess was right.
     */
    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null || !HASH_SHAPE.matcher(storedHash).matches()) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(storedHash);
        return MessageDigest.isEqual(digest(presented), expected);
    }

    public static boolean isWellFormedClientId(String candidate) {
        return candidate != null && CLIENT_ID_SHAPE.matcher(candidate).matches();
    }

    public static boolean isWellFormedSecret(String candidate) {
        return candidate != null && SECRET_SHAPE.matcher(candidate).matches();
    }

    /** The single token a partner configures: the client id, a full stop, then the secret. */
    public static String toBearerToken(String clientId, String secret) {
        return clientId + TOKEN_SEPARATOR + secret;
    }

    private static String randomToken(int bytes) {
        byte[] material = new byte[bytes];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every conformant JRE.
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
