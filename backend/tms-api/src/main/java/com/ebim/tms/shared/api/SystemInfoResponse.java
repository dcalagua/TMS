package com.ebim.tms.shared.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Public, non-sensitive service identification payload.
 *
 * <p>Deliberately excludes host names, versions of dependencies, database coordinates and
 * anything else that would help an unauthenticated caller fingerprint the deployment.
 *
 * <p>{@code commit} is the one deliberate exception, and it is a judged trade rather than an
 * oversight. Nothing else in this system could answer "which build is live?": the container
 * platform reports a build id, the Maven version is {@code 0.1.0-SNAPSHOT} on every revision, and
 * the repository holds no console credential from which either could be read. Every incident
 * starts with that question, so the answer is published on the one endpoint an operator can reach
 * without a token. It is a 12-character prefix - enough for {@code git show}, and it identifies a
 * revision only to somebody who already has the repository. It is {@code null} rather than a
 * guess wherever the build did not stamp one; see {@link SystemInfoController}.
 */
public record SystemInfoResponse(
        String application,
        String version,
        String commit,
        String status,
        List<String> profiles,
        OffsetDateTime timestamp) {
}
