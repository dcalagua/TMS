package com.ebim.tms.shared.api;

import com.ebim.tms.shared.config.TmsApiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and identification endpoint for the TMS API.
 *
 * <p>This is the one intentionally public business-path endpoint: it lets the frontend and
 * local tooling confirm that React is talking to the expected backend before any
 * authenticated call is attempted. Detailed operational data stays behind Actuator.
 *
 * <h2>Release identity</h2>
 *
 * <p>The endpoint also answers <em>which revision</em> is running, which nothing in this system
 * previously did. {@code version} cannot: it is the Maven version, {@code 0.1.0-SNAPSHOT} on
 * every commit ever built. The container platform's own build id is not a revision and is not
 * readable without console access, and this repository has none. So {@code commit} is resolved
 * here, from the first of these that carries a value:
 *
 * <ol>
 *   <li>{@code tms.release.commit} - the explicit setting, which as an environment variable is
 *       {@code TMS_RELEASE_COMMIT}. This is what a build system not listed below should set, and
 *       what a {@code docker build --build-arg} would bake in.</li>
 *   <li>{@code RENDER_GIT_COMMIT} - injected by Render into every service, so the backend
 *       identifies itself on the current deployment channel with no configuration at all.</li>
 *   <li>{@code build.commit} from {@code build-info.properties}, present when the Maven build was
 *       given the SHA (see {@code docs/operations/PROMOTION.md} for the {@code pom.xml} change
 *       that adds it). Baked into the jar, so it survives being run anywhere.</li>
 * </ol>
 *
 * <p>When none of them has a value the field is {@code null}, never {@code "unknown"} and never a
 * substitute such as the Maven version: an operator comparing what is deployed against what was
 * promoted must be able to tell "this build did not record its revision" from "this build is a
 * different revision". {@code scripts/ops/verify-deployment.sh} treats the absent case as a smoke
 * failure for exactly that reason.
 */
@RestController
@RequestMapping("${tms.api.base-path}/system")
@Tag(name = "System", description = "Service identification and liveness")
public class SystemInfoController {

    /** Explicit setting; resolves {@code TMS_RELEASE_COMMIT} through relaxed binding. */
    static final String COMMIT_PROPERTY = "tms.release.commit";

    /** Set by Render on every instance of a service deployed from a Git repository. */
    static final String RENDER_COMMIT_VARIABLE = "RENDER_GIT_COMMIT";

    /**
     * How much of the SHA is published. Twelve hexadecimal characters are unambiguous for
     * {@code git show} in a repository of this size and read back over a phone without error,
     * while a full 40 in a public payload is more fingerprint than the question needs.
     */
    static final int COMMIT_LENGTH = 12;

    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final TmsApiProperties apiProperties;
    private final Clock clock;

    public SystemInfoController(
            Environment environment,
            ObjectProvider<BuildProperties> buildProperties,
            TmsApiProperties apiProperties,
            Clock clock) {
        this.environment = environment;
        this.buildProperties = buildProperties;
        this.apiProperties = apiProperties;
        this.clock = clock;
    }

    @GetMapping("/info")
    @Operation(summary = "Service name, version, deployed commit, active profiles and server time")
    public SystemInfoResponse info() {
        return new SystemInfoResponse(
                apiProperties.applicationName(),
                resolveVersion(),
                resolveCommit(),
                "UP",
                List.of(environment.getActiveProfiles()),
                OffsetDateTime.now(clock));
    }

    /**
     * Reads the Maven-generated build info when present. Running from an IDE without the
     * {@code build-info} goal leaves it absent, which must not break the endpoint.
     */
    private String resolveVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build != null ? build.getVersion() : "unknown";
    }

    /** @return the deployed revision, shortened, or {@code null} if this build did not record one */
    private String resolveCommit() {
        BuildProperties build = buildProperties.getIfAvailable();
        return shorten(firstWithValue(
                environment.getProperty(COMMIT_PROPERTY),
                environment.getProperty(RENDER_COMMIT_VARIABLE),
                build != null ? build.get("commit") : null));
    }

    private static String firstWithValue(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    /**
     * Package-private and static so "an absent commit stays absent" is a claim a unit test can
     * check without standing up a context.
     */
    static String shorten(String commit) {
        if (commit == null) {
            return null;
        }
        return commit.length() <= COMMIT_LENGTH ? commit : commit.substring(0, COMMIT_LENGTH);
    }
}
