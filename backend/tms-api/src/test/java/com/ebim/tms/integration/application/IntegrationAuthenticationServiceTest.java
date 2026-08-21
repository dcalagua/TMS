package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.integration.domain.IntegrationClient;
import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.integration.infrastructure.IntegrationClientRepository;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * Credential verification and, above all, tenancy.
 *
 * <p>The central assertion of this class is the one in
 * {@link #companyComesFromTheCredentialAndNowhereElse}: the company an integration request runs in
 * is read off the credential, so there is no input - header, body or parameter - through which a
 * partner could reach another tenant. Every other test here guards a way that guarantee could be
 * lost: a revoked credential that still opens, a wrong secret that verifies, a deactivated company
 * that keeps serving.
 *
 * <p>No database is needed for any of it, so these run on every build - including the ones where
 * Docker is unavailable and the {@code @EnabledIf} integration suites are skipped.
 */
class IntegrationAuthenticationServiceTest {

    private static final UUID COMPANY_A = UUID.fromString("22222222-0000-4000-8000-0000000000a1");
    private static final UUID ORGANIZATION = UUID.fromString("22222222-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("22222222-0000-4000-8000-0000000000e1");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    private IntegrationClientRepository clientRepository;
    private CompanyScopeLoader companyScopeLoader;
    private IntegrationAuthenticationService service;

    @BeforeEach
    void setUp() {
        clientRepository = mock(IntegrationClientRepository.class);
        companyScopeLoader = mock(CompanyScopeLoader.class);
        service = new IntegrationAuthenticationService(clientRepository, companyScopeLoader, CLOCK);
    }

    @Test
    @DisplayName("a valid credential resolves to its own company, which the caller never supplied")
    void companyComesFromTheCredentialAndNowhereElse() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);

        IntegrationPrincipal principal = service.authenticate(token(client, secret));

        assertThat(principal.companyId()).isEqualTo(COMPANY_A);
        assertThat(principal.clientId()).isEqualTo(client.clientId());
        assertThat(principal.scopes()).containsExactly(IntegrationScope.ORDER_WRITE);
        // The company was looked up by the credential's own company id, not by anything the
        // caller sent - there is no other argument it could have come from.
        verify(companyScopeLoader).loadActiveCompanyScope(COMPANY_A);
    }

    @Test
    @DisplayName("the resolved scope carries no user permissions, so user endpoints stay closed")
    void machineHoldsNoUserPermissions() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);

        IntegrationPrincipal principal = service.authenticate(token(client, secret));

        assertThat(principal.companyScope().permissions()).isEmpty();
    }

    @Test
    @DisplayName("a wrong secret is refused, with the same message an unknown client id gets")
    void wrongSecretIsRefused() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);

        String wrongSecretMessage = messageOf(() -> service.authenticate(token(client, IntegrationSecrets.newSecret())));

        when(clientRepository.findByClientId("tmsc_" + "A".repeat(22))).thenReturn(Optional.empty());
        String unknownClientMessage = messageOf(() -> service.authenticate(
                "tmsc_" + "A".repeat(22) + "." + IntegrationSecrets.newSecret()));

        assertThat(wrongSecretMessage)
                .as("telling the two apart would let an attacker enumerate client ids")
                .isEqualTo(unknownClientMessage);
    }

    @Test
    @DisplayName("a revoked credential is refused even with the right secret")
    void revokedCredentialIsRefused() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);
        client.revoke(OffsetDateTime.now(CLOCK), ACTOR);

        assertThatThrownBy(() -> service.authenticate(token(client, secret)))
                .isInstanceOf(BadCredentialsException.class);
        verify(companyScopeLoader, never()).loadActiveCompanyScope(any());
    }

    @Test
    @DisplayName("a credential of a deactivated company stops working, exactly as a session does")
    void inactiveCompanyIsRefused() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);
        when(companyScopeLoader.loadActiveCompanyScope(COMPANY_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(token(client, secret)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("a credential with no scopes is refused at the door")
    void scopelessCredentialIsRefused() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A);

        assertThatThrownBy(() -> service.authenticate(token(client, secret)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("a malformed token never reaches the database")
    void malformedTokensAreRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.authenticate(null)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate("   ")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate("no-separator")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(".onlyASecret")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate("tmsc_short.tmss_short"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(IntegrationSecrets.newClientId() + "."))
                .isInstanceOf(BadCredentialsException.class);

        verify(clientRepository, never()).findByClientId(any());
    }

    @Test
    @DisplayName("a secret presented as the client id half does not authenticate anything")
    void halvesAreNotInterchangeable() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);

        assertThatThrownBy(() -> service.authenticate(secret + "." + client.clientId()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("the superseded secret is accepted while the rotation window is open")
    void rotationWindowIsHonoured() {
        String original = IntegrationSecrets.newSecret();
        String rotated = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(original, COMPANY_A, IntegrationScope.LOCATION_WRITE);
        client.rotateSecret(IntegrationSecrets.hash(rotated), OffsetDateTime.now(CLOCK).plusDays(7),
                OffsetDateTime.now(CLOCK), ACTOR);

        assertThat(service.authenticate(token(client, rotated)).companyId()).isEqualTo(COMPANY_A);
        assertThat(service.authenticate(token(client, original)).companyId()).isEqualTo(COMPANY_A);
    }

    @Test
    @DisplayName("last_used_at is stamped on first use and not rewritten on every call")
    void lastUsedIsThrottled() {
        String secret = IntegrationSecrets.newSecret();
        IntegrationClient client = registered(secret, COMPANY_A, IntegrationScope.ORDER_WRITE);

        service.authenticate(token(client, secret));
        OffsetDateTime firstUse = client.lastUsedAt();
        service.authenticate(token(client, secret));

        assertThat(firstUse).isNotNull();
        assertThat(client.lastUsedAt()).isEqualTo(firstUse);
    }

    private IntegrationClient registered(String secret, UUID companyId, IntegrationScope... scopes) {
        IntegrationClient client = new IntegrationClient(companyId, IntegrationSecrets.newClientId(), "Partner",
                null, IntegrationSecrets.hash(secret), ACTOR);
        client.replaceScopes(List.of(scopes), ACTOR);
        when(clientRepository.findByClientId(client.clientId())).thenReturn(Optional.of(client));
        when(companyScopeLoader.loadActiveCompanyScope(companyId)).thenReturn(Optional.of(scopeOf(companyId)));
        return client;
    }

    private static CompanyScope scopeOf(UUID companyId) {
        return new CompanyScope(companyId, "CO-A", "Company A", "America/Lima", ORGANIZATION, "ORG", "Org", Set.of());
    }

    private static String token(IntegrationClient client, String secret) {
        return IntegrationSecrets.toBearerToken(client.clientId(), secret);
    }

    private static String messageOf(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the credential to be refused");
        } catch (BadCredentialsException refused) {
            return refused.getMessage();
        }
    }
}
