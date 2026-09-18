package com.ebim.tms.platform.security;

import com.ebim.tms.platform.application.ProvisioningCaller;
import com.ebim.tms.platform.security.PlatformTokenVerifier.VerifiedToken;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a bearer token on {@code /internal/platform/**} into a {@link PlatformAuthentication}, or
 * into a problem document.
 *
 * <p>It authenticates and stops there. Whether the caller may create a tenant is decided afterwards
 * by method security against the scope authority, which keeps "who are you" and "may you" in
 * separate places - the same split the rest of this product uses.
 *
 * <p>A failure clears the security context before responding. A half-populated context that
 * survived a rejected request is how a later filter ends up trusting something nobody verified.
 */
public class PlatformAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    /**
     * The authority names the endpoints are annotated with. They are constants because an
     * annotation cannot read configuration, and the contract published to MasterAdmin fixes them.
     */
    static final String CREATE_AUTHORITY = "tms:tenant:create";
    static final String READ_AUTHORITY = "tms:tenant:read";

    private final PlatformTokenVerifier verifier;
    private final ApiExceptionResponder responder;
    private final PlatformM2mProperties properties;

    public PlatformAuthenticationFilter(PlatformTokenVerifier verifier, ApiExceptionResponder responder,
            PlatformM2mProperties properties) {
        this.verifier = verifier;
        this.responder = responder;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith(BEARER) || header.length() <= BEARER.length()) {
                throw new ProblemTypedException(ProblemType.UNAUTHENTICATED,
                        "A control-plane bearer token is required.");
            }
            VerifiedToken token = verifier.verify(header.substring(BEARER.length()).trim());
            List<SimpleGrantedAuthority> authorities = authoritiesOf(token);
            SecurityContextHolder.getContext().setAuthentication(new PlatformAuthentication(
                    new ProvisioningCaller(token.subject(), token.jti(), token.actorId(), token.actorRole()),
                    authorities));
            chain.doFilter(request, response);
        } catch (ProblemTypedException refused) {
            SecurityContextHolder.clearContext();
            responder.respond(request, response, refused);
        }
    }

    /**
     * The token's scopes become authorities unchanged, plus the canonical authority the endpoints
     * are annotated with.
     *
     * <p>That second step is what makes {@code EBIM_MASTERADMIN_M2M_CREATE_SCOPE} a real setting.
     * By default the configured scope and the canonical authority are the same string and this adds
     * nothing; if a deployment renames the scope, the annotation keeps working instead of silently
     * refusing every call. Renaming cannot widen anything - a scope the token does not carry
     * produces no authority.
     */
    private List<SimpleGrantedAuthority> authoritiesOf(VerifiedToken token) {
        java.util.LinkedHashSet<String> granted = new java.util.LinkedHashSet<>(token.scopes());
        if (token.scopes().contains(properties.createScope())) {
            granted.add(CREATE_AUTHORITY);
        }
        if (token.scopes().contains(properties.readScope())) {
            granted.add(READ_AUTHORITY);
        }
        return granted.stream().map(SimpleGrantedAuthority::new).toList();
    }
}
