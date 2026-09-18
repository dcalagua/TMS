package com.ebim.tms.platform.security;

import com.ebim.tms.platform.application.ProvisioningProperties;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
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
 * The control-plane chain: {@code /internal/platform/**} and nothing else.
 *
 * <p>A third chain rather than a branch inside an existing one, because the three credentials this
 * product accepts must not be able to become one another:
 *
 * <ul>
 *   <li>a <strong>human</strong> Supabase JWT authenticates against the Supabase JWKS on the
 *       catch-all chain, and can never reach this path - the matcher below claims it first, and this
 *       chain's only authenticator is the ES256 verifier;</li>
 *   <li>an <strong>integration client</strong> credential authenticates on {@code /integration/**}
 *       and produces a company-scoped machine principal. It has no meaning here;</li>
 *   <li>a <strong>control-plane</strong> token produces {@link PlatformAuthentication}, which is
 *       neither of the above and carries no company.</li>
 * </ul>
 *
 * <p>No CORS, and anonymous access disabled: this endpoint is called by a server, never by a
 * browser, so there is no origin to allow and no such thing as an unauthenticated caller who should
 * get as far as a controller. CSRF is off for the same reason it is off on the other machine chain -
 * there is no cookie session to ride.
 *
 * <p>Ordered after the integration chain. The two matchers cannot overlap, so the order is a
 * statement of intent rather than a tie-break: the catch-all human chain stays last, where it
 * belongs.
 */
@Configuration
@EnableConfigurationProperties({PlatformM2mProperties.class, ProvisioningProperties.class})
public class PlatformSecurityConfig {

    /** Everything under this prefix is control-plane traffic and nothing else is. */
    public static final String PLATFORM_PATH = "/internal/platform/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http,
            PlatformTokenVerifier verifier,
            PlatformM2mProperties properties,
            ApiExceptionResponder apiExceptionResponder,
            TmsAuthenticationEntryPoint authenticationEntryPoint) throws Exception {

        http
                .securityMatcher(PLATFORM_PATH)
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                // Scope is enforced here as well as by @PreAuthorize on the handlers, and the
                // duplication is deliberate. Method security throws inside the dispatcher, where
                // the application's global handler turns it into the generic "access denied"; a
                // control plane needs to be told that what is missing is a scope. Checking in the
                // chain puts the refusal where this chain's own handler can answer it, and leaves
                // the annotation in place as the check that survives a routing change.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                com.ebim.tms.platform.api.PlatformApiPaths.V1 + "/tenants")
                        .hasAuthority(PlatformAuthenticationFilter.CREATE_AUTHORITY)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                com.ebim.tms.platform.api.PlatformApiPaths.V1 + "/tenants/*")
                        .hasAuthority(PlatformAuthenticationFilter.READ_AUTHORITY)
                        .anyRequest().authenticated())
                .addFilterAfter(new PlatformAuthenticationFilter(verifier, apiExceptionResponder, properties),
                        SecurityContextHolderFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        // Authenticated but not allowed means exactly one thing here: the token
                        // carries no scope for this operation. The generic "access denied" would be
                        // true and useless - a control plane reading it cannot tell whether to mint
                        // a different token or to call somebody.
                        .accessDeniedHandler((request, response, denied) ->
                                apiExceptionResponder.respond(request, response,
                                        new ProblemTypedException(ProblemType.MISSING_SCOPE,
                                                "The token carries no scope granting this operation."))))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)));

        return http.build();
    }
}
