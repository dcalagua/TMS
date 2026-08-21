package com.ebim.tms.integration.security;

import com.ebim.tms.integration.application.IntegrationAuthenticationService;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.CompanyScopeDeniedException;
import com.ebim.tms.shared.security.CompanyScopeInvalidException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates an inbound integration request from its bearer credential, and binds the request
 * to the credential's company.
 *
 * <p>This is the machine-to-machine analogue of {@code BearerTokenAuthenticationFilter} plus
 * {@code CompanyScopeFilter}, collapsed into one because the two steps are one decision here:
 * verifying the credential <em>is</em> resolving the tenant. There is no header that selects a
 * company and therefore no header to validate against a membership - the strongest form of the
 * rule ADR-003 states, because the client is never asked.
 *
 * <p>An {@code X-Company-Id} that disagrees with the credential is refused rather than ignored.
 * A partner sending one has misunderstood the API, and silently overriding them would let that
 * misunderstanding survive until the day it matters.
 *
 * <p>The security context is cleared in a {@code finally} block: container threads are pooled, and
 * an authentication that outlived its request would attach this partner's company to the next one.
 */
public class IntegrationAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final IntegrationAuthenticationService authenticationService;
    private final ApiExceptionResponder responder;

    public IntegrationAuthenticationFilter(IntegrationAuthenticationService authenticationService,
            ApiExceptionResponder responder) {
        this.authenticationService = authenticationService;
        this.responder = responder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            // No credential at all: answered by the entry point as 401 with the standard problem
            // document, exactly like the user-facing chain answers a missing token.
            responder.respond(request, response, IntegrationAuthenticationService.rejection());
            return;
        }

        IntegrationPrincipal principal;
        try {
            principal = authenticationService.authenticate(header.substring(BEARER_PREFIX.length()));
        } catch (AuthenticationException refused) {
            responder.respond(request, response, refused);
            return;
        }

        try {
            requireMatchingCompanyHeader(request, principal);
        } catch (CompanyScopeInvalidException | CompanyScopeDeniedException mismatch) {
            responder.respond(request, response, mismatch);
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(new IntegrationAuthenticationToken(principal));
        SecurityContextHolder.setContext(scoped);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * {@code X-Company-Id} is not part of this API's contract. When it is absent - the normal case
     * - nothing happens. When it is present it must name the credential's own company, so that a
     * partner who copied it from the user-facing documentation is told, rather than quietly
     * writing into whichever tenant their key happens to belong to.
     */
    private static void requireMatchingCompanyHeader(HttpServletRequest request, IntegrationPrincipal principal) {
        String header = request.getHeader(ApiHeaders.COMPANY_ID);
        if (header == null || header.isBlank()) {
            return;
        }

        UUID requested;
        try {
            requested = UUID.fromString(header.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new CompanyScopeInvalidException(ApiHeaders.COMPANY_ID + " must be a company UUID.");
        }
        if (!requested.equals(principal.companyId())) {
            log.warn("Cross-tenant access refused: integration client {} requested company {} on {} {}",
                    principal.clientId(), requested, request.getMethod(), request.getRequestURI());
            throw new CompanyScopeDeniedException("This credential operates in a single company; "
                    + ApiHeaders.COMPANY_ID + " must be omitted or match it.");
        }
    }
}
