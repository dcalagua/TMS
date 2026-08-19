package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers an unauthenticated request with an RFC 9457 document instead of a login redirect.
 *
 * <p>It sets the {@code WWW-Authenticate} header the bearer-token scheme requires and then
 * hands the failure to the shared error handling, so a 401 raised in the filter chain has the
 * same body shape as a 400 raised in a controller.
 */
@Component
public class TmsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiExceptionResponder responder;

    public TmsAuthenticationEntryPoint(ApiExceptionResponder responder) {
        this.responder = responder;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        if (!(failure instanceof UnprovisionedPrincipalException)) {
            // Present for a token failure too, as RFC 6750 requires. The error code says only
            // that the token was not accepted - never whether the signature, the issuer, the
            // audience or the expiry was the reason, which would help someone probe with forgeries.
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    request.getHeader(HttpHeaders.AUTHORIZATION) != null
                            ? "Bearer error=\"invalid_token\""
                            : "Bearer");
        }
        responder.respond(request, response, failure);
    }
}
