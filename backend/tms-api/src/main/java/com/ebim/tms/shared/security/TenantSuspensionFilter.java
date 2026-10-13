package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The single server-side enforcement point of an operational suspension ({@code lifecycle.v1},
 * ADR-021): a company-scoped request with a method other than GET/HEAD/OPTIONS, for an organization
 * EBIM MasterAdmin has suspended, is refused with {@code 423 tenant-suspended} before any controller.
 *
 * <p>Registered in both chains that serve tenants, right after the company scope is bound -
 * {@link CompanyScopeFilter} for people, {@code IntegrationAuthenticationFilter} for partner machines -
 * so the browser API, the carrier portal and the partner API are covered by the same rule. The
 * MasterAdmin chain ({@code /internal/platform-provisioning/**}) does not carry it: that is the channel
 * that lifts the suspension, and it must stay open.
 *
 * <p>What stays allowed, on purpose:
 * <ul>
 *   <li>reads (GET/HEAD/OPTIONS): data is never hidden by a suspension, and exports go on;</li>
 *   <li>unscoped requests such as {@code /api/v1/me}: that is where the application learns about the
 *       suspension and can explain it. Every business write needs a company scope, so none slips
 *       through here ({@code TenantSuspensionCoverageTest}).</li>
 *   <li>login and session: the contract keeps login allowed. TMS has no login, refresh, logout or
 *       password route of its own - the browser talks to Supabase Auth directly (ADR-001), company
 *       switching is the {@code X-Company-Id} header plus {@code GET /me}, and display preferences
 *       live in the browser - so nothing of that reaches this filter
 *       ({@code TenantSuspensionCoverageTest} keeps it so). The one per-user session state the API
 *       stores, the notification inbox's read marks ({@link #SESSION_STATE_WRITES}), is exempt: it is
 *       the user's own inbox, not the tenant's operation.</li>
 * </ul>
 *
 * <p>Independent of {@link CommercialAccessFilter}: commercial is not operational access (D-14). It
 * fails closed - a gate that cannot answer refuses the write rather than letting it through - and
 * reads never consult it, so a broken gate cannot take reads down with it.
 */
public class TenantSuspensionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantSuspensionFilter.class);

    /** RFC 9110 safe methods TMS serves. TRACE is not served at all. */
    static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Writes that are per-user session state, not tenant operation, and stay allowed while suspended:
     * marking the caller's own notifications read. Matched under any API base path.
     */
    public static final List<String> SESSION_STATE_WRITES = List.of(
            "/**/notifications/*/read",
            "/**/notifications/read-all");

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final TenantOperationGate gate;
    private final ApiExceptionResponder responder;

    public TenantSuspensionFilter(TenantOperationGate gate, ApiExceptionResponder responder) {
        this.gate = gate;
        this.responder = responder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (READ_METHODS.contains(request.getMethod()) || isSessionStateWrite(request)) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<CompanyScope> scope = authentication instanceof CompanyScopedAuthentication scoped
                ? scoped.companyScope()
                : Optional.empty();
        if (scope.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<TenantOperationGate.Suspension> suspension;
        try {
            suspension = gate.currentSuspension(scope.get());
        } catch (RuntimeException failure) {
            log.error("Tenant lifecycle could not be read for company {}; refusing {} {}",
                    scope.get().companyId(), request.getMethod(), request.getRequestURI(), failure);
            responder.respond(request, response, failure);
            return;
        }
        if (suspension.isPresent()) {
            log.info("Tenant suspended ({}) for organization {}: {} {} refused", suspension.get().reason(),
                    scope.get().organizationId(), request.getMethod(), request.getRequestURI());
            responder.respond(request, response, new TenantSuspendedException(suspension.get().reason(),
                    "This organization's TMS operation is suspended. Data remains available for reading."));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether a POST is one of the {@link #SESSION_STATE_WRITES}. */
    static boolean isSessionStateWrite(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "POST".equals(request.getMethod())
                && SESSION_STATE_WRITES.stream().anyMatch(pattern -> PATHS.match(pattern, path));
    }
}
