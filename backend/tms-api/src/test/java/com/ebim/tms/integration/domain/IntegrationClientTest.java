package com.ebim.tms.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credential lifecycle: which secrets open a credential, and when they stop.
 *
 * <p>Every case here is one an operator will eventually hit - a rotation mid-deployment, a
 * revocation after a leak, a partner still using last month's key - and each is the kind of thing
 * that is quietly wrong until it is exploited.
 */
class IntegrationClientTest {

    private static final UUID COMPANY = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("11111111-0000-4000-8000-0000000000a1");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("only the issued secret opens a fresh credential")
    void freshCredential() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = client(secret);

        assertThat(client.acceptsSecret(secret, NOW)).isTrue();
        assertThat(client.acceptsSecret(IntegrationSecrets.newSecret(), NOW)).isFalse();
    }

    @Test
    @DisplayName("a rotation with a grace window accepts both secrets until it closes")
    void rotationGraceWindow() {
        String original = IntegrationSecrets.newSecret();
        String rotated = IntegrationSecrets.newSecret();
        IntegrationClient client = client(original);

        client.rotateSecret(IntegrationSecrets.hash(rotated), NOW.plusDays(7), NOW, ACTOR);

        assertThat(client.acceptsSecret(rotated, NOW)).isTrue();
        assertThat(client.acceptsSecret(original, NOW)).as("the partner has not redeployed yet").isTrue();
        assertThat(client.acceptsSecret(original, NOW.plusDays(7).plusSeconds(1)))
                .as("the window closed").isFalse();
        assertThat(client.acceptsSecret(rotated, NOW.plusDays(30))).isTrue();
    }

    @Test
    @DisplayName("a rotation with no grace window invalidates the old secret at once")
    void rotationWithoutGrace() {
        String original = IntegrationSecrets.newSecret();
        String rotated = IntegrationSecrets.newSecret();
        IntegrationClient client = client(original);

        client.rotateSecret(IntegrationSecrets.hash(rotated), null, NOW, ACTOR);

        assertThat(client.acceptsSecret(original, NOW)).isFalse();
        assertThat(client.acceptsSecret(rotated, NOW)).isTrue();
        assertThat(client.previousSecretExpiresAt()).isNull();
    }

    @Test
    @DisplayName("a grace window already in the past is treated as no window at all")
    void rotationWithExpiredGrace() {
        String original = IntegrationSecrets.newSecret();
        String rotated = IntegrationSecrets.newSecret();
        IntegrationClient client = client(original);

        client.rotateSecret(IntegrationSecrets.hash(rotated), NOW.minusDays(1), NOW, ACTOR);

        assertThat(client.acceptsSecret(original, NOW)).isFalse();
        assertThat(client.previousSecretExpiresAt()).isNull();
    }

    @Test
    @DisplayName("rotating to the same secret is refused rather than looking like a rotation")
    void rotationMustChangeTheSecret() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = client(secret);

        assertThatThrownBy(() -> client.rotateSecret(IntegrationSecrets.hash(secret), NOW.plusDays(1), NOW, ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("expiring the rotation window drops the superseded hash, so no stale secret lingers")
    void expireRotationWindow() {
        String original = IntegrationSecrets.newSecret();
        String rotated = IntegrationSecrets.newSecret();
        IntegrationClient client = client(original);
        client.rotateSecret(IntegrationSecrets.hash(rotated), NOW.plusDays(7), NOW, ACTOR);

        assertThat(client.expireRotationWindow(NOW.plusDays(1))).as("still inside the window").isFalse();
        assertThat(client.expireRotationWindow(NOW.plusDays(8))).isTrue();
        assertThat(client.previousSecretExpiresAt()).isNull();
        assertThat(client.acceptsSecret(original, NOW)).as("even rewinding the clock").isFalse();
    }

    @Test
    @DisplayName("revocation is immediate, terminal, and takes the grace window with it")
    void revocation() {
        String original = IntegrationSecrets.newSecret();
        String rotated = IntegrationSecrets.newSecret();
        IntegrationClient client = client(original);
        client.rotateSecret(IntegrationSecrets.hash(rotated), NOW.plusDays(7), NOW, ACTOR);

        client.revoke(NOW.plusHours(1), ACTOR);

        assertThat(client.isRevoked()).isTrue();
        assertThat(client.active()).as("a revoked credential is never active").isFalse();
        assertThat(client.acceptsSecret(original, NOW.plusHours(2)))
                .as("the superseded secret dies with the credential").isFalse();
        assertThat(client.revokedBy()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("scopes are diffed, so an unchanged scope keeps its own row")
    void scopesAreDiffed() {
        IntegrationClient client = client(IntegrationSecrets.newSecret());
        client.replaceScopes(List.of(IntegrationScope.LOCATION_WRITE, IntegrationScope.ORDER_WRITE), ACTOR);
        IntegrationClientScope locationScope = scopeRow(client, IntegrationScope.LOCATION_WRITE);

        client.replaceScopes(List.of(IntegrationScope.LOCATION_WRITE), ACTOR);

        assertThat(client.scopeValues()).containsExactly(IntegrationScope.LOCATION_WRITE);
        assertThat(client.hasScope(IntegrationScope.ORDER_WRITE)).isFalse();
        assertThat(scopeRow(client, IntegrationScope.LOCATION_WRITE))
                .as("the surviving scope was not recreated").isSameAs(locationScope);
    }

    @Test
    @DisplayName("replacing scopes with none leaves a credential that can do nothing")
    void scopesCanBeEmptied() {
        IntegrationClient client = client(IntegrationSecrets.newSecret());
        client.replaceScopes(List.of(IntegrationScope.ORDER_WRITE), ACTOR);

        client.replaceScopes(Set.of(), ACTOR);

        // The API refuses to create one this way; the entity still models it, and the
        // authenticator turns it into a rejection rather than a credential that authenticates
        // and then fails every endpoint.
        assertThat(client.scopeValues()).isEmpty();
    }

    private static IntegrationClient client(String secret) {
        return new IntegrationClient(COMPANY, IntegrationSecrets.newClientId(), "Partner WMS", null,
                IntegrationSecrets.hash(secret), ACTOR);
    }

    private static IntegrationClientScope scopeRow(IntegrationClient client, IntegrationScope scope) {
        return client.scopeRows().stream()
                .filter(row -> row.value().filter(scope::equals).isPresent())
                .findFirst()
                .orElseThrow();
    }
}
