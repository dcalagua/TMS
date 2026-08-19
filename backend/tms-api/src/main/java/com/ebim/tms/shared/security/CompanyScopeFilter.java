package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ApiHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request to a company, server-side.
 *
 * <p>This is the enforcement point of ADR-003's rule that "a client-supplied company id is
 * always validated against membership". The header selects; the principal's membership
 * snapshot decides. A company the caller holds no active membership in is refused here, before
 * any controller, service or repository sees it - so no query can be written that accidentally
 * trusts the header.
 *
 * <p>Runs immediately after the bearer-token filter, so the scope exists by the time method
 * security evaluates {@code @PreAuthorize}: authorities are the permissions of the selected
 * company and of no other (see {@link TmsAuthenticationToken}).
 *
 * <p>A request without the header is left unscoped rather than rejected, because endpoints
 * like {@code /api/v1/me} are principal-scoped and legitimately have no company. Endpoints
 * that do need one obtain it through {@link CompanyScopeArgumentResolver}, which fails the
 * request when it is absent.
 */
public class CompanyScopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CompanyScopeFilter.class);

    private final ApiExceptionResponder responder;

    public CompanyScopeFilter(ApiExceptionResponder responder) {
        this.responder = responder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        String header = request.getHeader(ApiHeaders.COMPANY_ID);
        if (!(context.getAuthentication() instanceof TmsAuthenticationToken token)
                || header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        UUID companyId;
        try {
            companyId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException notAUuid) {
            responder.respond(request, response, new CompanyScopeInvalidException(
                    ApiHeaders.COMPANY_ID + " must be a company UUID."));
            return;
        }

        Optional<CompanyScope> scope = token.getPrincipal().companyScope(companyId);
        if (scope.isEmpty()) {
            // The security-relevant event: a valid token asking for a tenant it does not belong
            // to. Logged with both ids so it can be correlated and investigated.
            log.warn("Cross-tenant access refused: app_user {} requested company {} on {} {}",
                    token.getPrincipal().appUserId(), companyId, request.getMethod(), request.getRequestURI());
            responder.respond(request, response, new CompanyScopeDeniedException(
                    "The selected company is not available to this account."));
            return;
        }

        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(token.withCompanyScope(scope.get()));
        SecurityContextHolder.setContext(scoped);
        try {
            chain.doFilter(request, response);
        } finally {
            // Container threads are pooled: the scoped context must not outlive this request.
            SecurityContextHolder.setContext(context);
        }
    }
}
