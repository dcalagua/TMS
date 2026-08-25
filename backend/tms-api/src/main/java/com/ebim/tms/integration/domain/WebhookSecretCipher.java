package com.ebim.tms.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts and decrypts webhook signing secrets at rest (migration V35).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Every other secret in TMS is hashed and never recovered - {@code IntegrationSecrets} says so
 * at length, and that remains the rule wherever the server only has to answer "is this the right
 * value". A webhook signature is the case where it cannot be the rule: the server has to
 * <em>compute</em> an HMAC from the secret on every send, so a value it cannot read is a value it
 * cannot sign with. The choice is therefore between encrypting the secret and having no signatures
 * at all, and unsigned webhooks are worth very little - a receiver that cannot tell TMS from anyone
 * who learned its URL is not really authenticating the sender.
 *
 * <h2>The scheme</h2>
 *
 * <p>AES-256-GCM, a fresh 96-bit IV per encryption, 128-bit authentication tag, stored as
 * base64({@code iv} || {@code ciphertext+tag}). GCM rather than CBC because it is authenticated:
 * ciphertext edited in the database fails to decrypt instead of silently producing a wrong secret
 * that would sign every delivery with garbage until somebody noticed.
 *
 * <p>The key comes from one deployment setting and is <b>not</b> per-tenant. A per-tenant key would
 * have to be stored somewhere too, and the only somewhere available is the same database - which is
 * the thing being defended against. What this defends against is stated plainly and not more: a
 * database dump alone, a stolen backup, a read-only SQL injection. It does not defend against an
 * attacker who already has the application's configuration, and no design that keeps the server
 * able to sign could.
 *
 * <h2>The key</h2>
 *
 * <p>Any configured string of at least 32 characters is accepted and reduced to a 256-bit key with
 * SHA-256. That is a key-derivation step, not a password one - the input is expected to be
 * high-entropy random material from a secret store, and a work factor would defend a low-entropy
 * input this setting is not supposed to hold. The minimum length is the enforceable half of that
 * expectation; the documentation states the rest.
 */
public final class WebhookSecretCipher {

    /** Recorded in {@code webhook_subscription.secret_algorithm}, so a re-encryption can be lazy. */
    public static final String ALGORITHM = "aes-gcm-256";

    /** Shorter than this and the setting was typed by a person rather than generated. */
    public static final int MIN_KEY_MATERIAL_LENGTH = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private WebhookSecretCipher(SecretKeySpec key) {
        this.key = key;
    }

    /**
     * @param keyMaterial the configured {@code tms.integration.webhooks.secret-key}
     * @throws IllegalArgumentException if it is missing or too short - the caller turns that into
     *     "webhooks are not configured in this deployment" rather than starting with a weak key
     */
    public static WebhookSecretCipher of(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.strip().length() < MIN_KEY_MATERIAL_LENGTH) {
            throw new IllegalArgumentException(
                    "webhook secret key must be at least " + MIN_KEY_MATERIAL_LENGTH + " characters");
        }
        byte[] digest = sha256(keyMaterial.strip());
        return new WebhookSecretCipher(new SecretKeySpec(digest, "AES"));
    }

    /** base64(iv || ciphertext+tag). One IV per call, never reused - GCM's one absolute rule. */
    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("could not encrypt the webhook secret", failure);
        }
    }

    /**
     * @throws IllegalStateException when the ciphertext does not authenticate - a wrong key, or a
     *     row that was edited. Both mean this subscription cannot sign, and the dispatcher's job is
     *     to say so and stop rather than to send something unverifiable
     */
    public String decrypt(String ciphertext) {
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("the stored webhook secret is not valid base64", malformed);
        }
        if (envelope.length <= IV_BYTES) {
            throw new IllegalStateException("the stored webhook secret is truncated");
        }
        byte[] iv = Arrays.copyOfRange(envelope, 0, IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(envelope, IV_BYTES, envelope.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            // Deliberately without the cause's message in the API path: it would distinguish "wrong
            // key" from "corrupt row" to anyone who could trigger it. The log gets the exception.
            throw new IllegalStateException("the stored webhook secret could not be decrypted", failure);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
