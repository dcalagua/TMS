package com.ebim.tms.orders.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The secret of a customer tracking link (ADR-029 T1, T2).
 *
 * <p>32 bytes from {@link SecureRandom}, encoded base64url without padding: 43 characters, 256 bits.
 * Nothing about it is derived from the order, the company or the time, so knowing one link says nothing
 * about any other. Only {@link #hash()} - the lower-case hex SHA-256 - is ever stored; a plain digest is
 * the right tool because the input already has 256 bits of entropy (a slow KDF protects passwords,
 * which this is not).
 *
 * <p>{@link #toString()} is redacted, so a token that ends up in a log message by accident does not end
 * up in the log.
 */
public final class TrackingToken {

    /** Raw entropy in bytes. */
    public static final int BYTES = 32;

    /** Characters of the encoded token: ceil(32 * 8 / 6). */
    public static final int LENGTH = 43;

    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9_-]{" + LENGTH + "}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String value;
    private final String hash;

    private TrackingToken(String value) {
        this.value = value;
        this.hash = hashOf(value);
    }

    /** A fresh token from the shared {@link SecureRandom}. */
    public static TrackingToken generate() {
        return generate(RANDOM);
    }

    /** A fresh token from {@code random} - a seam for tests, never a weaker source in production. */
    public static TrackingToken generate(SecureRandom random) {
        byte[] bytes = new byte[BYTES];
        Objects.requireNonNull(random, "random").nextBytes(bytes);
        return new TrackingToken(ENCODER.encodeToString(bytes));
    }

    /**
     * Whether {@code candidate} could be a token at all. A malformed one is refused before any database
     * read, which is both cheaper and one less way to probe the table.
     */
    public static boolean isWellFormed(String candidate) {
        return candidate != null && WELL_FORMED.matcher(candidate).matches();
    }

    /** The lower-case hex SHA-256 of a raw token's UTF-8 bytes: what {@code tms.tracking_link.token_hash} holds. */
    public static String hashOf(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** The raw token. Shown to the office once, in the creation response, and never stored. */
    public String value() {
        return value;
    }

    public String hash() {
        return hash;
    }

    @Override
    public String toString() {
        return "TrackingToken[redacted]";
    }
}
