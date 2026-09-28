package com.ebim.tms.iam.entitlements.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the entitlements receiver: its settings and the TMS receiver profile. */
@Configuration
@EnableConfigurationProperties(PlatformEntitlementsProperties.class)
public class PlatformEntitlementsConfig {

    @Bean
    public ReceiverProfile receiverProfile() {
        return ReceiverProfile.tms();
    }
}
