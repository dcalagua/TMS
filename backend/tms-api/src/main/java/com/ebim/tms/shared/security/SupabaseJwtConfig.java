package com.ebim.tms.shared.security;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Wires the Supabase JWT decoder into the application context.
 *
 * <p>Deliberately a separate configuration class from {@link SecurityConfig}: the filter chain
 * needs any {@link JwtDecoder}, while <em>this</em> class is the one that decides which keys
 * are trusted. Keeping them apart lets a slice test exercise the real chain against locally
 * signed tokens without ever contacting a key server, and without a profile switch inside
 * production code that could weaken a deployment by accident.
 *
 * <p>Consequently there is no {@code @Profile}, no {@code @ConditionalOnProperty} and no
 * "permit all" branch anywhere in the main sources. If the settings are missing, the context
 * fails to start - loudly, and with the names of the environment variables to set.
 */
@Configuration
@EnableConfigurationProperties(TmsSecurityProperties.class)
public class SupabaseJwtConfig {

    private static final Logger log = LoggerFactory.getLogger(SupabaseJwtConfig.class);

    /** Profiles treated as a real deployment, where the stricter URI rules apply. */
    static final String[] PRODUCTION_PROFILES = {"prod", "production", "staging"};

    @Bean
    public JwtDecoder jwtDecoder(TmsSecurityProperties properties, Environment environment) {
        boolean production = isProduction(environment);
        JwtDecoder decoder = SupabaseJwtDecoders.create(properties.jwt(), production);
        log.info("Supabase JWT verification enabled: issuer={}, audiences={}, production-rules={}",
                properties.jwt().issuerUri(), properties.jwt().audiences(), production);
        return decoder;
    }

    static boolean isProduction(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(active -> Arrays.stream(PRODUCTION_PROFILES).anyMatch(active::equalsIgnoreCase));
    }
}
