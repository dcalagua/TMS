package com.ebim.tms.integration.security;

import com.ebim.tms.integration.application.IntegrationAuthenticationService;
import com.ebim.tms.integration.application.IntegrationProperties;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * The machine-to-machine security chain, for {@code /integration/**} only.
 *
 * <h2>Why a separate chain rather than a second authentication method on the existing one</h2>
 *
 * <ol>
 *   <li><b>No shared credential surface.</b> A Supabase JWT is not accepted here and an
 *       integration credential is not accepted anywhere else. Neither kind of caller can reach
 *       the other's endpoints even by accident, and neither authentication method has to be
 *       written defensively against the other.</li>
 *   <li><b>No CORS.</b> There is no {@code cors()} configuration on this chain, deliberately: a
 *       partner integration is a server, not a browser, and an integration credential in a
 *       browser is a leaked credential. Omitting CORS means a page on any origin simply cannot
 *       call these endpoints.</li>
 *   <li><b>Different failure surface.</b> A missing user token and a missing partner credential
 *       need different messages, and this chain owns its own.</li>
 * </ol>
 *
 * <p>{@code @Order(1)} puts it ahead of {@code SecurityConfig}'s chain, which has no security
 * matcher and therefore must remain last.
 *
 * <p>Method security is not configured here: {@code @EnableMethodSecurity} on {@code SecurityConfig}
 * is application-wide, and the integration controllers use it with scope authorities exactly as
 * the user-facing ones use it with permission authorities.
 */
@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationSecurityConfig {

    /** Everything under this prefix is machine-to-machine and nothing else is. */
    public static final String INTEGRATION_PATH = "/integration/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityFilterChain integrationSecurityFilterChain(
            HttpSecurity http,
            IntegrationAuthenticationService authenticationService,
            ApiExceptionResponder apiExceptionResponder,
            TmsAuthenticationEntryPoint authenticationEntryPoint,
            TmsAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .securityMatcher(INTEGRATION_PATH)
                // No cookie session exists, so there is no CSRF to protect against; and a caller
                // that has to send a credential header cannot be ridden by a browser form post.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterAfter(new IntegrationAuthenticationFilter(authenticationService, apiExceptionResponder),
                        SecurityContextHolderFilter.class)
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
}
