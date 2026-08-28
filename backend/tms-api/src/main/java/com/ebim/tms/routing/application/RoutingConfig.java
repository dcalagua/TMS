package com.ebim.tms.routing.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link RoutingProperties}.
 *
 * <p>No bean selection, for {@code TrackingConfig}'s reason: the adapter chain is assembled from
 * whatever {@link com.ebim.tms.routing.domain.RoutingProviderAdapter} beans exist, and today
 * exactly one does. A vendor adapter arrives as another {@code @Component} - or as a
 * {@code @ConditionalOnProperty} one - and needs no change here.
 */
@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {
}
