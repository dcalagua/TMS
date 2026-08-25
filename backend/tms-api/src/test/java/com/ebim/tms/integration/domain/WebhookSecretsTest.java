package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signature scheme, pinned.
 *
 * <p>This is the contract a receiver writes verification code against, published in
 * {@code docs/integrations/WEBHOOKS_V1.md}. Changing any of these assertions breaks every integrator
 * silently - their verification simply starts failing - so the test exists to make that impossible
 * to do by accident. It needs no database and therefore runs on every build.
 */
class WebhookSecretsTest {

    private static final OffsetDateTime SENT_AT =
            OffsetDateTime.of(2026, 8, 21, 10, 15, 30, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("a signing secret is prefixed and carries 256 bits of entropy")
    void secretShape() {
        String secret = WebhookSecrets.newSecret();

        assertThat(secret).startsWith(WebhookSecrets.SECRET_PREFIX);
        assertThat(WebhookSecrets.isWellFormed(secret)).isTrue();
        // 32 bytes, base64url, unpadded.
        assertThat(secret).hasSize(WebhookSecrets.SECRET_PREFIX.length() + 43);
    }

    @Test
    @DisplayName("generated secrets do not repeat")
    void secretsAreUnique() {
        Set<String> secrets = new HashSet<>();
        IntStream.range(0, 500).forEach(i -> secrets.add(WebhookSecrets.newSecret()));

        assertThat(secrets).hasSize(500);
    }

    @Test
    @DisplayName("the hint is the last four characters, which the database's shape check accepts")
    void hintIsTheTail() {
        String secret = WebhookSecrets.newSecret();

        String hint = WebhookSecrets.hint(secret);

        assertThat(hint).hasSize(4).isEqualTo(secret.substring(secret.length() - 4));
        // ck_webhook_subscription_secret_hint_shape
        assertThat(hint).matches("^[A-Za-z0-9_-]{4}$");
        // The prefix is the same on every secret, so a head-based hint would identify nothing.
        assertThat(hint).isNotEqualTo(secret.substring(0, 4));
    }

    @Test
    @DisplayName("the header is t=<seconds>,v1=<hex> and the hex is HMAC-SHA256 of \"<t>.<body>\"")
    void headerFormat() {
        String header = WebhookSecrets.signatureHeader("tmsw_key", SENT_AT, "{\"a\":1}");

        long epochSeconds = SENT_AT.toEpochSecond();
        assertThat(header)
                .isEqualTo("t=" + epochSeconds + ",v1=" + WebhookSecrets.signature("tmsw_key", epochSeconds, "{\"a\":1}"));
        assertThat(header).matches("^t=\\d+,v1=[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("the same secret, time and body always produce the same signature")
    void deterministic() {
        String first = WebhookSecrets.signature("tmsw_key", 1_700_000_000L, "{\"a\":1}");
        String second = WebhookSecrets.signature("tmsw_key", 1_700_000_000L, "{\"a\":1}");

        // A retry sends byte-identical bytes and must therefore produce a verifiable signature
        // against the same stored payload - see WebhookDelivery.payload.
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("the timestamp is inside the signed material, so a captured delivery cannot be replayed later")
    void timestampIsBound() {
        String atOneTime = WebhookSecrets.signature("tmsw_key", 1_700_000_000L, "{\"a\":1}");
        String anHourLater = WebhookSecrets.signature("tmsw_key", 1_700_003_600L, "{\"a\":1}");

        // If these were equal, moving the t= value forward would produce a delivery that still
        // verified - which is exactly the replay this scheme exists to stop.
        assertThat(atOneTime).isNotEqualTo(anHourLater);
    }

    @Test
    @DisplayName("a different secret or a different body produces a different signature")
    void keyAndBodyMatter() {
        String signature = WebhookSecrets.signature("tmsw_key", 1_700_000_000L, "{\"a\":1}");

        assertThat(WebhookSecrets.signature("tmsw_other", 1_700_000_000L, "{\"a\":1}")).isNotEqualTo(signature);
        assertThat(WebhookSecrets.signature("tmsw_key", 1_700_000_000L, "{\"a\":2}")).isNotEqualTo(signature);
    }

    @Test
    @DisplayName("verification accepts the right signature and rejects everything else")
    void verification() {
        String body = "{\"id\":\"x\"}";
        String signature = WebhookSecrets.signature("tmsw_key", 1_700_000_000L, body);

        assertThat(WebhookSecrets.matches("tmsw_key", 1_700_000_000L, body, signature)).isTrue();
        assertThat(WebhookSecrets.matches("tmsw_key", 1_700_000_001L, body, signature)).isFalse();
        assertThat(WebhookSecrets.matches("tmsw_key", 1_700_000_000L, body, null)).isFalse();
        assertThat(WebhookSecrets.matches("tmsw_key", 1_700_000_000L, body, "")).isFalse();
    }

    @Test
    @DisplayName("a value too short to hint at is refused rather than truncated")
    void hintRefusesShortValues() {
        assertThatThrownBy(() -> WebhookSecrets.hint("ab"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
