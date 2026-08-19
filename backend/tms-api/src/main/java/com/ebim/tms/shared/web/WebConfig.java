package com.ebim.tms.shared.web;

import com.ebim.tms.shared.security.CompanyScopeArgumentResolver;
import java.util.List;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer wiring: the correlation filter and the company-scope controller parameter.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Registered ahead of the Spring Security chain so that rejected requests are traceable
     * too - a 401 with no correlation id is a support ticket nobody can answer.
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 5);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CompanyScopeArgumentResolver());
    }
}
