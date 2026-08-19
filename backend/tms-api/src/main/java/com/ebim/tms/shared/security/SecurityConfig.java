package com.ebim.tms.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Baseline HTTP security for the stateless TMS API.
 *
 * <p>Step 01 scope: deny by default, stateless sessions, no browser login flows, and a
 * short allow-list of endpoints that must work before authentication exists (liveness,
 * API documentation). Supabase JWT validation, {@code app_user}/membership resolution and
 * business authorization are added in Step 03; until then every business endpoint answers
 * 401 rather than being silently open.
 *
 * <p>Frontend hiding is never authorization: this chain, the services and the repository
 * queries are.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoints that are safe to expose without authentication. */
    static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, PublicApiPaths publicApiPaths)
            throws Exception {
        return http
                // Stateless token API: no cookie session to protect, so CSRF is not applicable.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(publicApiPaths.systemInfo()).permitAll()
                        .anyRequest().authenticated())
                // Answer 401 instead of redirecting to a login page that does not exist.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults()))
                .build();
    }
}
