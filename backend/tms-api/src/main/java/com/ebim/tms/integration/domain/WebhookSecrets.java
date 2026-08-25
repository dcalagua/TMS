package com.ebim.tms.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generation of webhook signing secrets and the signature every delivery carries (migration V35).
 *
 * <h2>The signature</h2>
 *
 * <p>Each POST carries {@code X-TMS-Signature: t=<unix seconds>,v1=<hex>}, where the hex is
 * HMAC-SHA-256 over {@code "<t>.<body>"} keyed with the subscription's secret. The receiver
 * recomputes it and compares in constant time; if it matches, the bytes came from TMS and were not
 * edited on the way.
 *
 * <h2>Why the timestamp is inside the signed material</h2>
 *
 * <p>Signing the body alone produces a signature that stays valid forever, so anyone who captured
 * one delivery could replay it at any later moment and the receiver could not tell. Binding the
 * time into the MAC lets the receiver reject anything older than its own tolerance - five minutes
 * is the usual choice - and the timestamp cannot be edited to defeat that, because editing it
 * breaks the MAC. The scheme is deliberately the one Stripe and GitHub made familiar: an integrator
 * who has verified a webhook before will recognise it, and a scheme nobody recognises is a scheme
 * people skip verifying.
 *
 * <h2>The version prefix</h2>
 *
 * <p>{@code v1=} exists so a second algorithm can be introduced by sending both for a while, rather
 * than by breaking every receiver on one deployment day. It is the whole migration plan for this
 * scheme and it costs three characters.
 *
 * <h2>What never happens</h2>
 *
 * <p>The secret is shown exactly once, by the call that creates or rotates it. Afterwards only
 * {@link #hint} of it is ever rendered - four characters, so a screen can say which secret is in
 * place without being able to sign anything. Unlike an {@code IntegrationSecrets} credential, the
 * secret itself is recoverable by the server: an HMAC cannot be computed from a hash. It is held
 * encrypted (see {@code WebhookSecretCipher}) and the trade-off is documented on
 * {@code tms.webhook_subscription.secret_ciphertext}.
 */
public final class WebhookSecrets {

    public static final String SECRET_PREFIX = "tmsw_";

    /** The signature scheme's version tag, sent as {@code v1=} and recorded in the docs. */
    public static final String SIGNATURE_VERSION = "v1";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    private static final int HINT_LENGTH = 4;

    private static final Pattern SECRET_SHAPE = Pattern.compile("^tmsw_[A-Za-z0-9_-]{43}$");

    /** One shared, thread-safe {@link SecureRandom}, for the reason {@code IntegrationSecrets} states. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSecrets() {}

    /** {@code tmsw_} + base64url of 32 random bytes: 256 bits, the key length HMAC-SHA-256 wants. */
    public static String newSecret() {
        byte[] material = new byte[SECRET_BYTES];
        RANDOM.nextBytes(material);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    public static boolean isWellFormed(String candidate) {
        return candidate != null && SECRET_SHAPE.matcher(candidate).matches();
    }

    /**
     * The last four characters, which is all any screen ever sees of a secret.
     *
     * <p>The tail rather than the head: every secret starts with the same five-character prefix, so
     * the first four characters would identify nothing. Four of 43 base64url characters leaves 216
     * bits unknown, which is not a meaningful head start for anybody.
     */
    public static String hint(String secret) {
        if (secret == null || secret.length() < HINT_LENGTH) {
            throw new IllegalArgumentException("secret is too short to hint at");
        }
        return secret.substring(secret.length() - HINT_LENGTH);
    }

    /**
     * The full {@code X-TMS-Signature} header value for one delivery.
     *
     * @param sentAt the moment of this attempt - not of the event, which may be hours older. A
     *     receiver's replay window is about how long ago the bytes were put on the wire
     */
    public static String signatureHeader(String secret, OffsetDateTime sentAt, String body) {
        long epochSeconds = sentAt.toEpochSecond();
        return "t=" + epochSeconds + "," + SIGNATURE_VERSION + "=" + signature(secret, epochSeconds, body);
    }

    /** HMAC-SHA-256 of {@code "<epochSeconds>.<body>"}, lower-case hex. */
    public static String signature(String secret, long epochSeconds, String body) {
        String signedPayload = epochSeconds + "." + body;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            // HmacSHA256 is mandatory in every conformant JRE, and the key is never empty.
            throw new IllegalStateException("HMAC-SHA-256 is not available", impossible);
        }
    }

    /**
     * Whether a presented signature matches, compared in constant time.
     *
     * <p>TMS never verifies its own signatures in production - it only produces them - so this
     * exists for the tests that prove the scheme is what the documentation says it is, and for the
     * day an inbound signed callback needs the same primitive.
     */
    public static boolean matches(String secret, long epochSeconds, String body, String presented) {
        if (presented == null) {
            return false;
        }
        byte[] expected = signature(secret, epochSeconds, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
