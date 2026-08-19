package com.ebim.tms.shared.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The only thing a security test replaces: where the signing keys come from.
 *
 * <p>Everything else - the filter chain, the JWT claim validators, the converter, the company
 * scope filter, method security, the error documents - is the production wiring. That is the
 * point: a test setup that swapped the chain would prove nothing about the chain that ships.
 *
 * <p>{@link SupabaseJwtConfig}, which reads the JWKS coordinates from configuration, is simply
 * not imported here; it has its own unit test for the fail-fast rules.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestConfiguration {

    @Bean
    public JwtDecoder jwtDecoder() {
        return TestJwts.decoder();
    }

    @Bean
    public StubPrincipalLoader principalLoader() {
        return new StubPrincipalLoader()
                .register(TestPrincipals.planner())
                .register(TestPrincipals.viewerOfTwoCompanies())
                .register(TestPrincipals.outsider())
                .register(TestPrincipals.withoutMemberships());
        // TestPrincipals.UNPROVISIONED_AUTH_USER is deliberately not registered: it stands for
        // a Supabase account with no active tms.app_user behind it.
    }
}
