package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a company-scoped request when its organization may not operate commercially.
 *
 * <p>Runs right after the company scope is bound - {@link CompanyScopeFilter} for people,
 * {@code IntegrationAuthenticationFilter} for partner machines - so it sees the scope that method
 * security will see, and before any controller. A request with no company scope (such as
 * {@code /api/v1/me}) is not gated: that is where the application learns who the user is and can
 * explain a suspension.
 *
 * <p>It fails closed: a gate that cannot answer refuses the request rather than letting it through.
 */
public class CommercialAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CommercialAccessFilter.class);

    private final CommercialAccessGate gate;
    private final ApiExceptionResponder responder;

    public CommercialAccessFilter(CommercialAccessGate gate, ApiExceptionResponder responder) {
        this.gate = gate;
        this.responder = responder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<CompanyScope> scope = authentication instanceof CompanyScopedAuthentication scoped
                ? scoped.companyScope()
                : Optional.empty();
        if (scope.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        CommercialAccessGate.Verdict verdict;
        try {
            verdict = gate.check(scope.get());
        } catch (RuntimeException failure) {
            log.error("Commercial access could not be decided for company {}; refusing", scope.get().companyId(),
                    failure);
            responder.respond(request, response, failure);
            return;
        }
        if (!verdict.allowed()) {
            log.warn("Commercial access suspended ({}) for organization {}: {} {} refused",
                    verdict.reason(), scope.get().organizationId(), request.getMethod(), request.getRequestURI());
            responder.respond(request, response, new CommercialAccessSuspendedException(
                    "This organization's TMS subscription is not active. Contact your EBIM account manager."));
            return;
        }
        chain.doFilter(request, response);
    }
}
