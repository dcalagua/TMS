package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one place TMS stores a secret it can read back, so the properties of that storage are pinned.
 *
 * <p>The point of each assertion below is the same: if the ciphertext ever became readable without
 * the key, or editable without detection, the reason for encrypting rather than hashing would stop
 * holding.
 */
class WebhookSecretCipherTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";

    @Test
    @DisplayName("a secret survives a round trip")
    void roundTrip() {
        WebhookSecretCipher cipher = WebhookSecretCipher.of(KEY);
        String secret = WebhookSecrets.newSecret();

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    @DisplayName("the ciphertext does not contain the plaintext")
    void ciphertextHidesThePlaintext() {
        WebhookSecretCipher cipher = WebhookSecretCipher.of(KEY);
        String secret = WebhookSecrets.newSecret();

        String ciphertext = cipher.encrypt(secret);

        assertThat(ciphertext).doesNotContain(secret);
        // The prefix would be enough for a scanner to spot a secret in a database dump.
        assertThat(ciphertext).doesNotContain(WebhookSecrets.SECRET_PREFIX);
    }

    @Test
    @DisplayName("encrypting the same secret twice produces different ciphertext")
    void freshIvEveryTime() {
        WebhookSecretCipher cipher = WebhookSecretCipher.of(KEY);

        String first = cipher.encrypt("tmsw_same");
        String second = cipher.encrypt("tmsw_same");

        // Equal ciphertexts would mean a reused IV, which in GCM is the one failure that destroys
        // the mode's guarantees outright. It would also let anyone with read access see that two
        // subscriptions share a secret.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("the wrong key cannot decrypt")
    void wrongKeyFails() {
        String ciphertext = WebhookSecretCipher.of(KEY).encrypt("tmsw_value");

        assertThatThrownBy(() -> WebhookSecretCipher.of(OTHER_KEY).decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class)
                // The message must not distinguish "wrong key" from "corrupt row" to a caller.
                .hasMessageNotContaining("key");
    }

    @Test
    @DisplayName("edited ciphertext is refused rather than silently decrypted to something else")
    void tamperIsDetected() {
        WebhookSecretCipher cipher = WebhookSecretCipher.of(KEY);
        byte[] envelope = Base64.getDecoder().decode(cipher.encrypt("tmsw_value"));
        // Flip one bit of the last byte, which is inside the authentication tag.
        envelope[envelope.length - 1] ^= 0x01;
        String edited = Base64.getEncoder().encodeToString(envelope);

        // This is why GCM and not CBC: an unauthenticated mode would hand back a wrong secret and
        // TMS would sign every delivery with it until somebody noticed the signatures failing.
        assertThatThrownBy(() -> cipher.decrypt(edited)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a truncated or non-base64 value is refused with a clear failure")
    void malformedCiphertext() {
        WebhookSecretCipher cipher = WebhookSecretCipher.of(KEY);

        assertThatThrownBy(() -> cipher.decrypt("not base64!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[8])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a key that is missing or too short is refused, never quietly accepted")
    void keyMaterialIsChecked() {
        assertThatThrownBy(() -> WebhookSecretCipher.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookSecretCipher.of("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookSecretCipher.of("   " + "x".repeat(20) + "   "))
                .isInstanceOf(IllegalArgumentException.class);

        // Exactly at the minimum is accepted.
        assertThat(WebhookSecretCipher.of("x".repeat(WebhookSecretCipher.MIN_KEY_MATERIAL_LENGTH))).isNotNull();
    }
}
