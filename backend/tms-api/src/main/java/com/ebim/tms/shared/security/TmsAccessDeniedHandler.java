package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Logs an authorization failure raised in the filter chain and answers it with the shared
 * error document.
 *
 * <p>The response says only that access was denied; it never names the permission that was
 * required, because that would let a caller enumerate the authorization model. The permission
 * and the actor <em>are</em> logged - the operator needs exactly what the caller must not have.
 */
@Component
public class TmsAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(TmsAccessDeniedHandler.class);

    private final ApiExceptionResponder responder;

    public TmsAccessDeniedHandler(ApiExceptionResponder responder) {
        this.responder = responder;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        logDenial(request);
        responder.respond(request, response, denied);
    }

    static void logDenial(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof TmsAuthenticationToken token) {
            log.warn("Denied {} {} for app_user {} in company scope {}: held permissions {}",
                    request.getMethod(), request.getRequestURI(), token.getPrincipal().appUserId(),
                    token.companyScope().map(CompanyScope::companyId).orElse(null),
                    token.getAuthorities());
        } else {
            log.warn("Denied {} {} for an unresolved principal", request.getMethod(), request.getRequestURI());
        }
    }
}
