package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;

/**
 * The tenant suspension filter on its own (lifecycle.v1, ADR-021): writes of a suspended organization
 * are refused, reads never consult the gate, unscoped requests pass, and a gate that cannot answer
 * refuses the write.
 */
class TenantSuspensionFilterTest {

    private static final TenantOperationGate.Suspension NON_PAYMENT =
            new TenantOperationGate.Suspension("NON_PAYMENT", Instant.parse("2026-10-13T11:00:00Z"));

    private final List<Exception> responded = new ArrayList<>();
    private final ApiExceptionResponder responder = new ApiExceptionResponder((request, response, handler, failure) -> {
        responded.add(failure);
        response.setStatus(423);
        return new ModelAndView();
    });

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CompanyScope scope() {
        return TestPrincipals.planner().companyScope(TestPrincipals.NORTH_LIMA).orElseThrow();
    }

    /** A partner credential's shape: company-scoped, machine. */
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

    @ParameterizedTest(name = "{0} of a suspended organization is refused before the business code")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void writesAreRefused(String method) throws Exception {
        List<UUID> asked = new ArrayList<>();
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> {
            asked.add(s.organizationId());
            return Optional.of(NON_PAYMENT);
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(method, "/api/v1/orders"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request must not reach the business code").isNull();
        assertThat(asked).containsExactly(TestPrincipals.NORTH_ORG);
        assertThat(responded).singleElement().isInstanceOfSatisfying(TenantSuspendedException.class,
                suspended -> assertThat(suspended.reason()).isEqualTo("NON_PAYMENT"));
    }

    @ParameterizedTest(name = "{0} is a read: allowed, and the gate is not even asked")
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void readsPassWithoutConsultingTheGate(String method) throws Exception {
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> {
            throw new AssertionError("a read must not consult the gate");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(method, "/api/v1/orders"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(responded).isEmpty();
    }

    @ParameterizedTest(name = "POST {0} is the user's own session state: allowed while suspended, gate not asked")
    @ValueSource(strings = {"/api/v1/notifications/7f1c0000-0000-4000-8000-000000000001/read",
            "/api/v1/notifications/read-all"})
    void sessionStateWritesPass(String path) throws Exception {
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> {
            throw new AssertionError("a session-state write must not consult the gate");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(responded).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} is not session state: refused while suspended")
    @org.junit.jupiter.params.provider.CsvSource({
        "DELETE, /api/v1/notifications/read-all",
        "POST, /api/v1/notifications/read-all/extra",
        "POST, /api/v1/orders/123/read",
        "POST, /api/v1/notifications"})
    void lookalikesAreRefused(String method, String path) throws Exception {
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> Optional.of(NON_PAYMENT), responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(responded).singleElement().isInstanceOf(TenantSuspendedException.class);
    }

    @Test
    @DisplayName("an active organization writes through untouched")
    void activePasses() throws Exception {
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> Optional.empty(), responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/integration/v1/orders"), new MockHttpServletResponse(),
                chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(responded).isEmpty();
    }

    @Test
    @DisplayName("an unscoped request is not gated: no business write exists without a company scope")
    void unscopedIsNotGated() throws Exception {
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> {
            throw new AssertionError("the gate must not be consulted without a company scope");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", null));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/me"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("a gate that cannot answer refuses the write (fail closed)")
    void failsClosed() throws Exception {
        TenantSuspensionFilter filter = new TenantSuspensionFilter(s -> {
            throw new IllegalStateException("database unavailable");
        }, responder);
        SecurityContextHolder.getContext().setAuthentication(new Scoped(scope()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(responded).singleElement().isInstanceOf(IllegalStateException.class);
    }
}
