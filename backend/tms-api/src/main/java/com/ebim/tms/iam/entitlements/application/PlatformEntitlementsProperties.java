package com.ebim.tms.iam.entitlements.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the entitlements receiver, under {@code tms.platform-entitlements}.
 *
 * <p>The receiver shares the MasterAdmin security chain, key and switch with provisioning
 * ({@code tms.platform-provisioning.*}); the only thing of its own is the environment it answers
 * for. Empty (the default) means every PUT is {@code 422 ENVIRONMENT_MISMATCH}: a deployment that
 * has not said whether it is DEV or QAS must not accept a snapshot issued for either.
 *
 * @param environment {@code DEV}, {@code QAS}, {@code DEMO} or {@code PRD}; {@code TMS_ENTITLEMENTS_ENVIRONMENT}
 */
@ConfigurationProperties(prefix = "tms.platform-entitlements")
public record PlatformEntitlementsProperties(String environment) {

    public PlatformEntitlementsProperties {
        environment = environment == null ? "" : environment.trim();
    }
}
