package com.ebim.tms.integration.api;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the integration module's controller parameter resolver.
 *
 * <p>A module-local {@code WebMvcConfigurer} rather than an addition to {@code shared.web.WebConfig}:
 * {@code shared} must not depend on a business module ({@code ModuleBoundaryTest}), and Spring
 * composes every {@code WebMvcConfigurer} it finds, so a module can extend the web layer without
 * the web layer knowing the module exists.
 */
@Configuration
public class IntegrationWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new IntegrationPrincipalArgumentResolver());
    }
}
