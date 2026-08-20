package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credential scheme, pinned.
 *
 * <p>These are the properties a partner credential's security rests on. They need no database, so
 * they are verified on every build regardless of whether Docker is available - which matters,
 * because "the hash was actually one-way" is not something to discover in production.
 */
class IntegrationSecretsTest {

    @Test
    @DisplayName("a client id is prefixed and carries 128 bits of entropy")
    void clientIdShape() {
        String clientId = IntegrationSecrets.newClientId();

        assertThat(clientId).startsWith(IntegrationSecrets.CLIENT_ID_PREFIX);
        assertThat(IntegrationSecrets.isWellFormedClientId(clientId)).isTrue();
        // 16 bytes, base64url, unpadded.
        assertThat(clientId).hasSize(IntegrationSecrets.CLIENT_ID_PREFIX.length() + 22);
    }

    @Test
    @DisplayName("a secret is prefixed and carries 256 bits of entropy")
    void secretShape() {
        String secret = IntegrationSecrets.newSecret();

        assertThat(secret).startsWith(IntegrationSecrets.SECRET_PREFIX);
        assertThat(IntegrationSecrets.isWellFormedSecret(secret)).isTrue();
        assertThat(secret).hasSize(IntegrationSecrets.SECRET_PREFIX.length() + 43);
    }

    @Test
    @DisplayName("generated values do not repeat")
    void valuesAreUnique() {
        Set<String> clientIds = new HashSet<>();
        Set<String> secrets = new HashSet<>();
        IntStream.range(0, 500).forEach(i -> {
            clientIds.add(IntegrationSecrets.newClientId());
            secrets.add(IntegrationSecrets.newSecret());
        });

        // A collision at this sample size would mean the generator is not what it claims to be.
        assertThat(clientIds).hasSize(500);
        assertThat(secrets).hasSize(500);
    }

    @Test
    @DisplayName("the stored hash is lower-case hex SHA-256, the shape the database constrains")
    void hashShape() {
        String hash = IntegrationSecrets.hash(IntegrationSecrets.newSecret());

        assertThat(hash).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("the hash does not contain the secret in any recoverable form")
    void hashDoesNotEchoTheSecret() {
        String secret = IntegrationSecrets.newSecret();

        String hash = IntegrationSecrets.hash(secret);

        assertThat(hash).doesNotContain(secret);
        assertThat(hash).doesNotContain(secret.substring(IntegrationSecrets.SECRET_PREFIX.length(), 20));
    }

    @Test
    @DisplayName("hashing is deterministic, so a stored hash keeps verifying")
    void hashingIsDeterministic() {
        String secret = IntegrationSecrets.newSecret();

        assertThat(IntegrationSecrets.hash(secret)).isEqualTo(IntegrationSecrets.hash(secret));
    }

    @Test
    @DisplayName("the right secret verifies and every wrong one does not")
    void verification() {
        String secret = IntegrationSecrets.newSecret();
        String hash = IntegrationSecrets.hash(secret);

        assertThat(IntegrationSecrets.matches(secret, hash)).isTrue();
        assertThat(IntegrationSecrets.matches(IntegrationSecrets.newSecret(), hash)).isFalse();
        assertThat(IntegrationSecrets.matches(secret + "x", hash)).isFalse();
        assertThat(IntegrationSecrets.matches(secret.substring(0, secret.length() - 1), hash)).isFalse();
        assertThat(IntegrationSecrets.matches(null, hash)).isFalse();
        assertThat(IntegrationSecrets.matches(secret, null)).isFalse();
    }

    @Test
    @DisplayName("a stored value that is not a SHA-256 digest never verifies")
    void malformedStoredHashNeverMatches() {
        String secret = IntegrationSecrets.newSecret();

        // Guards against the worst possible bug: a row whose hash column was written with the
        // plaintext would otherwise 'verify' by string equality in a naive implementation.
        assertThat(IntegrationSecrets.matches(secret, secret)).isFalse();
        assertThat(IntegrationSecrets.matches(secret, "")).isFalse();
        assertThat(IntegrationSecrets.matches(secret, IntegrationSecrets.hash(secret).toUpperCase())).isFalse();
    }

    @Test
    @DisplayName("malformed halves are rejected before any lookup")
    void shapeChecks() {
        assertThat(IntegrationSecrets.isWellFormedClientId("tmsc_short")).isFalse();
        assertThat(IntegrationSecrets.isWellFormedClientId(IntegrationSecrets.newSecret())).isFalse();
        assertThat(IntegrationSecrets.isWellFormedSecret(IntegrationSecrets.newClientId())).isFalse();
        assertThat(IntegrationSecrets.isWellFormedSecret("tmss_" + "!".repeat(43))).isFalse();
        assertThat(IntegrationSecrets.isWellFormedClientId(null)).isFalse();
        assertThat(IntegrationSecrets.isWellFormedSecret(null)).isFalse();
    }

    @Test
    @DisplayName("the bearer token splits unambiguously, because the separator is not base64url")
    void bearerTokenIsUnambiguous() {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();

        String token = IntegrationSecrets.toBearerToken(clientId, secret);
        int separator = token.indexOf(IntegrationSecrets.TOKEN_SEPARATOR);

        assertThat(token.chars().filter(c -> c == IntegrationSecrets.TOKEN_SEPARATOR).count()).isEqualTo(1);
        assertThat(token.substring(0, separator)).isEqualTo(clientId);
        assertThat(token.substring(separator + 1)).isEqualTo(secret);
    }

    @Test
    @DisplayName("hashing null is a programming error, not a silently empty hash")
    void hashRejectsNull() {
        assertThatThrownBy(() -> IntegrationSecrets.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
