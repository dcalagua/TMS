package com.ebim.tms.tracking.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link TrackingProperties}.
 *
 * <p>No bean selection here, unlike {@code EvidenceStorageConfig}, which switches between a real
 * store and a disabled one: the provider implementation is an ordinary component and there is
 * exactly one of it. The settings themselves are stated on the boot log by
 * {@link TrackingIngestionService}, which is the class they govern - how much of a feed a
 * deployment keeps should be readable off a startup log rather than deduced from a row count a
 * month later.
 */
@Configuration
@EnableConfigurationProperties(TrackingProperties.class)
public class TrackingConfig {
}
