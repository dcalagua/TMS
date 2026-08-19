package com.ebim.tms.shared.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Public, non-sensitive service identification payload.
 *
 * <p>Deliberately excludes host names, versions of dependencies, database coordinates and
 * anything else that would help an unauthenticated caller fingerprint the deployment.
 */
public record SystemInfoResponse(
        String application,
        String version,
        String status,
        List<String> profiles,
        OffsetDateTime timestamp) {
}
