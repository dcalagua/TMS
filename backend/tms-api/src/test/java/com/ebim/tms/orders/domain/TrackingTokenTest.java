package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-029 T1/T2: the token is 256 random bits, stored only as its SHA-256, and never printed. */
class TrackingTokenTest {

    @Test
    @DisplayName("a token is 32 random bytes in base64url: 43 characters, no padding")
    void shape() {
        TrackingToken token = TrackingToken.generate();

        assertThat(token.value()).hasSize(TrackingToken.LENGTH).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token.value())).hasSize(32);
        assertThat(TrackingToken.isWellFormed(token.value())).isTrue();
    }

    @Test
    @DisplayName("ten thousand tokens are ten thousand different tokens with ten thousand different hashes")
    void tokensDoNotRepeat() {
        Set<String> values = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            TrackingToken token = TrackingToken.generate();
            values.add(token.value());
            hashes.add(token.hash());
        }
        assertThat(values).hasSize(10_000);
        assertThat(hashes).hasSize(10_000);
    }

    @Test
    @DisplayName("the bits are spread: across many tokens every bit position takes both values about half the time")
    void bitsAreUniform() {
        int samples = 4_000;
        int[] ones = new int[256];
        for (int i = 0; i < samples; i++) {
            byte[] bytes = Base64.getUrlDecoder().decode(TrackingToken.generate().value());
            for (int bit = 0; bit < 256; bit++) {
                if ((bytes[bit / 8] >> (bit % 8) & 1) == 1) {
                    ones[bit]++;
                }
            }
        }
        // 4000 fair coin flips: mean 2000, sigma ~31.6. Six sigma either side is far outside chance.
        for (int count : ones) {
            assertThat(count).isBetween(1_810, 2_190);
        }
    }

    @Test
    @DisplayName("the stored hash is the lower-case hex SHA-256 of the token, and is not the token")
    void hashIsSha256() throws Exception {
        TrackingToken token = TrackingToken.generate(new SecureRandom());
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.value().getBytes(StandardCharsets.UTF_8)));

        assertThat(token.hash()).isEqualTo(expected).matches("[0-9a-f]{64}").isNotEqualTo(token.value());
        assertThat(TrackingToken.hashOf(token.value())).isEqualTo(token.hash());
        // A known vector, so the encoding (UTF-8, lower-case hex) can never drift from what V74 checks.
        assertThat(TrackingToken.hashOf("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("toString never prints the token")
    void redacted() {
        TrackingToken token = TrackingToken.generate();
        assertThat(token.toString()).doesNotContain(token.value()).doesNotContain(token.hash()).contains("redacted");
    }

    @Test
    @DisplayName("anything that is not 43 base64url characters is malformed and never reaches the database")
    void malformed() {
        String good = TrackingToken.generate().value();
        assertThat(TrackingToken.isWellFormed(null)).isFalse();
        assertThat(TrackingToken.isWellFormed("")).isFalse();
        assertThat(TrackingToken.isWellFormed(good.substring(1))).isFalse();
        assertThat(TrackingToken.isWellFormed(good + "A")).isFalse();
        assertThat(TrackingToken.isWellFormed(good.substring(1) + "=")).isFalse();
        assertThat(TrackingToken.isWellFormed(good.substring(1) + "/")).isFalse();
        assertThat(TrackingToken.isWellFormed("' OR 1=1 --" + good.substring(11))).isFalse();
        assertThat(TrackingToken.isWellFormed(good.substring(0, 42) + "é")).isFalse();
    }
}
