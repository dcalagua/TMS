package com.ebim.tms.iam.provisioning.security;

import com.ebim.tms.iam.provisioning.api.PlatformProvisioningPaths;
import com.ebim.tms.iam.provisioning.api.ProvisioningErrorResponse;
import com.ebim.tms.iam.provisioning.application.ProvisioningErrorCode;
import com.ebim.tms.shared.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import tools.jackson.databind.ObjectMapper;

/**
 * The third security chain: EBIM MasterAdmin, for {@code /internal/platform-provisioning/**} only.
 *
 * <h2>Why a chain of its own</h2>
 *
 * <p>The same reasons {@code IntegrationSecurityConfig} gives, one level up. A Supabase user token is
 * not accepted here, a partner integration credential is not accepted here, and a MasterAdmin token
 * is accepted nowhere else - so no caller of one surface can reach another by accident, and the
 * control-plane token cannot reach the business API even if a {@code @PreAuthorize} were wrong.
 *
 * <pre>
 *   HIGHEST_PRECEDENCE + 1   IntegrationSecurityConfig            /integration/**
 *   HIGHEST_PRECEDENCE + 2   THIS                                 /internal/platform-provisioning/**
 *   (last, no matcher)       SecurityConfig                       everything else
 * </pre>
 *
 * <p>It must sit ahead of {@code SecurityConfig}, which has no matcher and would otherwise claim the
 * prefix and demand a Supabase token for it.
 *
 * <h2>Registered even when switched off</h2>
 *
 * <p>With {@code tms.platform-provisioning.enabled=false} the chain is still registered: the health
 * probe answers {@code 503 unavailable} and every other call {@code 503 M2M_NOT_CONFIGURED}. Leaving
 * it out would hand the prefix to the user chain - "off" must mean "refused", never "served by
 * someone else".
 *
 * <h2>What is authorized where</h2>
 *
 * <p>One scope per operation, declared here where it is structural: a method added under the prefix
 * without its line is caught by {@code anyRequest().denyAll()}, not left open to any MasterAdmin
 * token. {@code actor_role} never becomes an authority. No CORS: the caller is a server.
 */
@Configuration
@EnableConfigurationProperties(PlatformProvisioningProperties.class)
public class PlatformProvisioningSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(PlatformProvisioningSecurityConfig.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public SecurityFilterChain platformProvisioningSecurityFilterChain(
            HttpSecurity http, PlatformProvisioningProperties properties, ObjectMapper objectMapper) throws Exception {

        ContractErrorWriter errors = new ContractErrorWriter(objectMapper, properties.enabled());

        http
                .securityMatcher(PlatformProvisioningPaths.ALL)
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)));

        if (!properties.enabled()) {
            log.info("MasterAdmin provisioning is OFF (tms.platform-provisioning.enabled=false): {} "
                    + "refuses every call and its health probe answers 503.", PlatformProvisioningPaths.ALL);
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, PlatformProvisioningPaths.HEALTH).permitAll()
                    .anyRequest().denyAll());
            return http.build();
        }

        log.info("MasterAdmin provisioning is ON at {}: ES256 tokens from iss={} for aud={}",
                PlatformProvisioningPaths.ALL, properties.issuer(), properties.audience());

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PlatformProvisioningPaths.HEALTH).permitAll()
                        .requestMatchers(HttpMethod.POST, PlatformProvisioningPaths.TENANTS)
                                .hasAuthority(PlatformScopes.AUTHORITY_TENANT_CREATE)
                        .requestMatchers(HttpMethod.GET, PlatformProvisioningPaths.TENANT)
                                .hasAuthority(PlatformScopes.AUTHORITY_TENANT_READ)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(ignoringHealth())
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(jwt -> jwt
                                // Not a bean: a second JwtDecoder bean would compete with the
                                // Supabase one the user chain relies on.
                                .decoder(PlatformM2mJwtDecoders.create(properties))
                                .jwtAuthenticationConverter(PlatformProvisioningAuthentication::from)));

        return http.build();
    }

    /**
     * The health probe is anonymous by contract, so a token sent to it - valid, expired or garbage -
     * is not even read. Otherwise a stale token in a monitor would turn "is it up?" into a 401.
     */
    private static BearerTokenResolver ignoringHealth() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> PlatformProvisioningPaths.HEALTH.equals(request.getRequestURI())
                ? null
                : delegate.resolve(request);
    }

    /**
     * Writes the contract's error body for failures raised in the filter chain, where no controller
     * advice runs. It says that a token was rejected, never why - signature, issuer, audience, expiry
     * and lifetime all answer {@code INVALID_M2M_TOKEN}, which gives nothing to someone probing with
     * forgeries.
     */
    static final class ContractErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

        private final ObjectMapper objectMapper;
        private final boolean enabled;

        ContractErrorWriter(ObjectMapper objectMapper, boolean enabled) {
            this.objectMapper = objectMapper;
            this.enabled = enabled;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
                throws IOException {
            if (!enabled) {
                write(response, ProvisioningErrorCode.M2M_NOT_CONFIGURED);
                return;
            }
            boolean presented = failure instanceof OAuth2AuthenticationException;
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, presented ? "Bearer error=\"invalid_token\"" : "Bearer");
            write(response, presented ? ProvisioningErrorCode.INVALID_M2M_TOKEN : ProvisioningErrorCode.UNAUTHENTICATED);
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.access.AccessDeniedException failure) throws IOException {
            if (!enabled) {
                write(response, ProvisioningErrorCode.M2M_NOT_CONFIGURED);
                return;
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean tenantOperation = request.getRequestURI().startsWith(PlatformProvisioningPaths.TENANTS);
            write(response, authentication instanceof PlatformProvisioningAuthentication && tenantOperation
                    ? ProvisioningErrorCode.MISSING_SCOPE
                    : ProvisioningErrorCode.ACCESS_DENIED);
        }

        private void write(HttpServletResponse response, ProvisioningErrorCode code) throws IOException {
            response.setStatus(code.httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            objectMapper.writeValue(response.getOutputStream(),
                    ProvisioningErrorResponse.of(code, List.of(), CorrelationId.current().orElse(null)));
        }
    }
}
