package com.ebim.tms.integration.application;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers {@link WebhookProperties} and turns on scheduling for the dispatcher (migration V35).
 *
 * <p>{@link EnableScheduling} arrives here rather than on the application class because the webhook
 * dispatcher is the product's first and only scheduled task, and putting the annotation next to it
 * keeps that visible. Nothing else in TMS runs on a timer; when something does, this is the
 * annotation it will find already present, and the {@code @Scheduled} method it should not be added
 * beside without reading {@code WebhookDispatchScheduler}'s comment about what a scheduled task may
 * assume about tenancy.
 *
 * <p>The state of the feature is stated on the boot log for the reason {@code TrackingConfig} gives:
 * whether a deployment can deliver webhooks should be readable off a startup log rather than
 * deduced from an empty table a week later.
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
@EnableScheduling
public class WebhookConfig {

    private static final Logger log = LoggerFactory.getLogger(WebhookConfig.class);

    private final WebhookProperties properties;

    public WebhookConfig(WebhookProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void statePolicy() {
        if (!properties.configured()) {
            log.info("Outbound webhooks are disabled: tms.integration.webhooks.secret-key is not set. "
                    + "Partners can still consume GET /integration/v1/shipments/events by polling.");
            return;
        }
        log.info("Outbound webhooks are enabled: up to {} attempts per delivery, retrying from {} up to {}, "
                        + "polling every {}, suspending an endpoint after {} consecutive failed deliveries",
                properties.maxAttempts(), properties.retryBaseDelay(), properties.retryMaxDelay(),
                properties.pollInterval(), properties.suspendAfterConsecutiveFailures());
        if (properties.allowInsecureTargets()) {
            log.warn("tms.integration.webhooks.allow-insecure-targets is on: http:// endpoints will be accepted. "
                    + "This must not be set in production - a webhook body carries operational data and a "
                    + "signature is not encryption.");
        }
        if (properties.allowPrivateNetworkTargets()) {
            log.warn("tms.integration.webhooks.allow-private-network-targets is on: endpoints inside this "
                    + "deployment's own network will be accepted. This is a server-side request forgery risk "
                    + "and is intended for development only.");
        }
    }
}
