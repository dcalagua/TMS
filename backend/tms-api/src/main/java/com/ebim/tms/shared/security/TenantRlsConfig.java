package com.ebim.tms.shared.security;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link TenantScopedDataSource} in front of whatever {@link DataSource} Spring Boot
 * built, so that tenant Row Level Security applies without any module having to know about it.
 *
 * <p>Wrapping the existing bean rather than declaring a replacement keeps Hikari's
 * configuration, metrics and health indicator exactly as they were: the pool is still the
 * pool, and this only decorates the connections it hands out.
 *
 * <p>Flyway shares this {@code DataSource}. That is intentional and safe - migrations run
 * before any request exists, so there is no company scope to apply and the connection is
 * handed over untouched, as the schema owner.
 */
@Configuration
public class TenantRlsConfig {

    @Bean
    public static BeanPostProcessor tenantScopedDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof TenantScopedDataSource)) {
                    return new TenantScopedDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
