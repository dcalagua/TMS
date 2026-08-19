package com.ebim.tms.shared.security;

import com.ebim.tms.shared.config.TmsApiProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves the small set of business-path URLs that are intentionally public, keeping the
 * configured API base path in one place instead of hard-coding {@code /api/v1} in the
 * security chain.
 */
@Component
public class PublicApiPaths {

    private final TmsApiProperties properties;

    public PublicApiPaths(TmsApiProperties properties) {
        this.properties = properties;
    }

    /** Liveness/identification endpoint used by the frontend before sign-in. */
    public String systemInfo() {
        return properties.basePath() + "/system/info";
    }
}
