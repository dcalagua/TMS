package com.ebim.tms.platform.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for what a provisioning call records.
 *
 * @param retainRequestSnapshot whether the request body is stored beside its hash. Off by default,
 *     for the reason {@code tms.integration} gives for the same setting: the payload carries the
 *     tenant's administrator email and registration details, and copying personal data into an
 *     append-only table is a decision an operator makes deliberately. The hash is always stored, so
 *     idempotency works either way; what the snapshot buys is being able to answer "what exactly did
 *     MasterAdmin send us" months later
 */
@ConfigurationProperties(prefix = "tms.platform.provisioning")
public record ProvisioningProperties(boolean retainRequestSnapshot) {

    public ProvisioningProperties {
        // Explicit so the default survives a partially populated binding rather than depending on
        // the primitive's zero value being the safe one by luck.
    }
}
