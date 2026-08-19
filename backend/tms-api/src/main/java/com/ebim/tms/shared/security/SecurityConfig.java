package com.ebim.tms.shared.security;

import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security for the stateless TMS API.
 *
 * <p>The chain is deny-by-default: everything that is not on the short public allow-list needs
 * a Supabase-issued JWT that passes signature, issuer, audience and expiry validation, and
 * that maps to an active {@code tms.app_user}. Company selection and permission checks happen
 * after that, in {@link CompanyScopeFilter} and in method security.
 *
 * <p>What this class deliberately does not do:
 *
 * <ul>
 *   <li>no form login, HTTP Basic, session or "remember me" - the only credential is a bearer
 *       token, so there is no second authentication path to secure;</li>
 *   <li>no {@code permitAll()} on a business path other than the unauthenticated service
 *       identification endpoint;</li>
 *   <li>no profile-conditional relaxation. Test support supplies a different key source, never
 *       a different chain.</li>
 * </ul>
 *
 * <p>Frontend hiding is never authorization: this chain, the services and the repository
 * queries are.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Endpoints that must answer before authentication exists. Health is here for container
     * probes and load balancers; {@code /actuator/info} is not, because build metadata is not
     * something an anonymous caller needs.
     */
    static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
    };

    /** API documentation. Public in development, authenticated in production (see {@link PublicApiPaths}). */
    static final String[] DOCUMENTATION_ENDPOINTS = {
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            PublicApiPaths publicApiPaths,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
            TmsAuthenticationEntryPoint authenticationEntryPoint,
            TmsAccessDeniedHandler accessDeniedHandler,
            ApiExceptionResponder apiExceptionResponder,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        http
                // Stateless token API: no cookie session to protect, so CSRF is not applicable.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_ENDPOINTS).permitAll();
                    if (publicApiPaths.documentationIsPublic()) {
                        auth.requestMatchers(DOCUMENTATION_ENDPOINTS).permitAll();
                    }
                    auth.requestMatchers(publicApiPaths.systemInfo()).permitAll()
                            .anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // Company selection is validated against membership immediately after the token
                // is accepted, so authorities are company-scoped before any authorization runs.
                .addFilterAfter(new CompanyScopeFilter(apiExceptionResponder), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)));

        return http.build();
    }

    /**
     * Cross-origin access for the React application.
     *
     * <p>Origins are an explicit allow-list and the default is empty, so a deployment that
     * forgets to configure them serves no browser other than a same-origin one - the safe
     * failure. Credentials are not allowed because the session is a bearer token in a header,
     * not a cookie; allowing them would add CSRF exposure for no benefit.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(TmsSecurityProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return source;
        }
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException(
                    "tms.security.cors.allowed-origins must list exact origins; wildcards are not accepted");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
                ApiHeaders.COMPANY_ID,
                ApiHeaders.CORRELATION_ID,
                ApiHeaders.REQUEST_ID));
        configuration.setExposedHeaders(List.of(ApiHeaders.CORRELATION_ID));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(1800L);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
