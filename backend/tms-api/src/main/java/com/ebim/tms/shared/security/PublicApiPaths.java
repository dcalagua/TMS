package com.ebim.tms.shared.security;

import com.ebim.tms.shared.config.TmsApiProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves the small set of URLs that are intentionally reachable without authentication,
 * keeping the configured API base path in one place instead of hard-coding {@code /api/v1} in
 * the security chain.
 *
 * <p>The list is short by design and every entry is justified. Adding one is a security
 * decision, not a routing convenience.
 */
@Component
public class PublicApiPaths {

    private final TmsApiProperties properties;

    public PublicApiPaths(TmsApiProperties properties) {
        this.properties = properties;
    }

    /**
     * Liveness/identification endpoint used by the frontend before sign-in, so an operator can
     * tell "the backend is down" from "my token is rejected". Returns no tenant or user data.
     */
    public String systemInfo() {
        return properties.basePath() + "/system/info";
    }

    /** @see TmsApiProperties#publicDocumentation() */
    public boolean documentationIsPublic() {
        return properties.publicDocumentation();
    }
}
