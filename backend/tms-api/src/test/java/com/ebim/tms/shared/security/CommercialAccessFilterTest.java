package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;

/**
 * The commercial access filter on its own: it consults the gate only for a company-scoped caller -
 * a person or a partner machine alike - and answers the refusal through the shared error responder.
 */
class CommercialAccessFilterTest {

    private final List<Exception> responded = new ArrayList<>();
    private final ApiExceptionResponder responder = new ApiExceptionResponder((request, response, handler, failure) -> {
        responded.add(failure);
        response.setStatus(403);
        return new ModelAndView();
    });

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CompanyScope scope() {
        return TestPrincipals.planner().companyScope(TestPrincipals.NORTH_LIMA).orElseThrow();
    }

    /** Any company-scoped authentication - the shape a partner credential has too. */
    private static final class Scoped extends TestingAuthenticationToken implements MachineAuthentication {
        private final CompanyScope scope;

        Scoped(CompanyScope scope) {
            super("partner", null);
            this.scope = scope;
            setAuthenticated(true);
        }

        @Override
        public Optional<CompanyScope> companyScope() {
            return Optional.of(scope);
        }

        @Override
        public String machineActorLabel() {
            return "integration:partner";
        }
    }

    @Test
    @DisplayName("a scoped caller whose organization is suspended is refused before the chain continues")
    void refusesSuspended() throws Exception {
        List<UUID> asked = new ArrayList<>();
        CommercialAccessFilter filter = new CommercialAccessFilter(s -> {
            asked.add(s.organizationId());
            return CommercialAccessGate.Verdict.suspended("APP_INACTIVE");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/integration/v1/orders"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request must not reach the business code").isNull();
        assertThat(asked).containsExactly(TestPrincipals.NORTH_ORG);
        assertThat(responded).singleElement().isInstanceOf(CommercialAccessSuspendedException.class);
    }

    @Test
    @DisplayName("an allowed organization passes through untouched")
    void passesAllowed() throws Exception {
        CommercialAccessFilter filter = new CommercialAccessFilter(s -> CommercialAccessGate.Verdict.ALLOWED, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(responded).isEmpty();
    }

    @Test
    @DisplayName("an unscoped caller (/me, before a company is chosen) is not gated at all")
    void unscopedIsNotGated() throws Exception {
        CommercialAccessFilter filter = new CommercialAccessFilter(s -> {
            throw new AssertionError("the gate must not be consulted without a company scope");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", null));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("a gate that cannot answer fails closed")
    void failsClosed() throws Exception {
        CommercialAccessFilter filter = new CommercialAccessFilter(s -> {
            throw new IllegalStateException("database unavailable");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(responded).singleElement().isInstanceOf(IllegalStateException.class);
    }
}
