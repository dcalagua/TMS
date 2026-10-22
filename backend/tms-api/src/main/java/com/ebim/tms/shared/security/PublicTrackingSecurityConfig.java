package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The fourth security chain: the anonymous area, {@code ${tms.api.base-path}/public/**} (ADR-029 T7).
 *
 * <pre>
 *   HIGHEST_PRECEDENCE + 1   IntegrationSecurityConfig            /integration/**
 *   HIGHEST_PRECEDENCE + 2   PlatformProvisioningSecurityConfig   /internal/platform-provisioning/**
 *   HIGHEST_PRECEDENCE + 3   THIS                                 ${base}/public/**
 *   (last, no matcher)       SecurityConfig                       everything else
 * </pre>
 *
 * <h2>Why a chain of its own, and why it reads no token</h2>
 *
 * <p>The customer tracking link is the first path that serves tenant data to a caller with no
 * session. Putting a {@code permitAll()} for it in the user chain would leave it beside the bearer
 * filter, the company-scope filter and the commercial and suspension filters - all of which would
 * then have to be reasoned about for an anonymous caller. Here there is nothing to reason about: no
 * {@code oauth2ResourceServer}, so an {@code Authorization} header is not even read and a Supabase token
 * neither helps nor hurts; the request is anonymous, so {@code TenantScopedDataSource} hands it the owner
 * connection and the service binds the tenant itself, from the link (ADR-029 decision 3).
 *
 * <h2>Exactly one route</h2>
 *
 * <p>{@code GET} of {@link PublicApiPaths#trackingLink()} is permitted; everything else under the prefix
 * - another method, another path, a deeper path - is {@code denyAll()} and answers the same
 * {@code 404 resource-not-found} an unknown token gets. A controller added under {@code /public} without a
 * line here is refused, not published.
 *
 * <p>Headers: frames denied, {@code nosniff}, {@code no-referrer} (the token is in the URL),
 * {@code Cache-Control: no-store} (Spring Security's default cache headers) and
 * {@code X-Robots-Tag: noindex, nofollow}. CORS is the same explicit allow-list as the user chain: the
 * page that calls this endpoint is the TMS frontend.
 */
@Configuration
public class PublicTrackingSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    public SecurityFilterChain publicTrackingSecurityFilterChain(
            HttpSecurity http,
            PublicApiPaths publicApiPaths,
            ApiExceptionResponder apiExceptionResponder,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        // Whatever is refused in this area looks exactly like an unknown token: no 401 that would
        // invite a credential, no 403 that would confirm something exists.
        NotFoundAnswer notFound = new NotFoundAnswer(apiExceptionResponder);

        http
                .securityMatcher(publicApiPaths.publicArea())
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, publicApiPaths.trackingLink()).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(notFound)
                        .accessDeniedHandler(notFound))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true))
                        .addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow")));

        return http.build();
    }

    /** The one refusal of this chain: {@code 404 resource-not-found}, the same as an unknown token. */
    static final class NotFoundAnswer implements org.springframework.security.web.AuthenticationEntryPoint,
            org.springframework.security.web.access.AccessDeniedHandler {

        private final ApiExceptionResponder responder;

        NotFoundAnswer(ApiExceptionResponder responder) {
            this.responder = responder;
        }

        @Override
        public void commence(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                org.springframework.security.core.AuthenticationException failure) throws java.io.IOException {
            responder.respond(request, response, new ResourceNotFoundException("Not found."));
        }

        @Override
        public void handle(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                org.springframework.security.access.AccessDeniedException failure) throws java.io.IOException {
            responder.respond(request, response, new ResourceNotFoundException("Not found."));
        }
    }
}
