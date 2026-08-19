package com.ebim.tms.shared.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Baseline application beans.
 *
 * <p>The {@link Clock} is injected rather than using {@code OffsetDateTime.now()} directly so
 * that time-dependent business rules (order windows, frequencies, planning dates) stay
 * testable without clock stubbing hacks.
 */
@Configuration
@EnableConfigurationProperties(TmsApiProperties.class)
public class ApplicationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
