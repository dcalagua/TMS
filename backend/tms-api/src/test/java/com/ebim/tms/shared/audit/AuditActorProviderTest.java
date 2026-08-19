package com.ebim.tms.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TestPrincipals;
import com.ebim.tms.shared.security.TmsAuthenticationToken;
import com.ebim.tms.shared.security.TmsPrincipal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The actor context that later steps stamp into {@code created_by} / {@code updated_by}.
 */
class AuditActorProviderTest {

    private final AuditActorProvider provider = new AuditActorProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the actor is the resolved app_user, never a value from the request")
    void actorComesFromThePrincipal() {
        TmsPrincipal principal = TestPrincipals.planner();
        authenticate(TmsAuthenticationToken.authenticated(token(), principal));

        AuditActor actor = provider.current().orElseThrow();

        assertThat(actor.appUserId()).isEqualTo(principal.appUserId());
        assertThat(actor.email()).isEqualTo(principal.email());
        assertThat(actor.company()).isEmpty();
    }

    @Test
    @DisplayName("once a company is selected, the actor carries the tenant the change belongs to")
    void actorCarriesTheSelectedCompany() {
        TmsPrincipal principal = TestPrincipals.planner();
        CompanyScope scope = principal.companies().getFirst();
        authenticate(TmsAuthenticationToken.authenticated(token(), principal).withCompanyScope(scope));

        AuditActor actor = provider.current().orElseThrow();

        assertThat(actor.companyId()).isEqualTo(scope.companyId());
        assertThat(actor.organizationId()).isEqualTo(scope.organizationId());
    }

    @Test
    @DisplayName("an unauthenticated context yields no actor rather than a fabricated one")
    void noActorWithoutAuthentication() {
        assertThat(provider.current()).isEmpty();
        assertThatThrownBy(provider::requireAppUserId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no authenticated actor");
    }

    @Test
    @DisplayName("a foreign authentication is not mistaken for a TMS principal")
    void foreignAuthenticationYieldsNoActor() {
        authenticate(new TestingAuthenticationToken("someone", "credentials"));

        assertThat(provider.current()).isEmpty();
    }

    private static void authenticate(org.springframework.security.core.Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Jwt token() {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"), Map.of("sub", TestPrincipals.PLANNER_AUTH_USER.toString()));
    }
}
