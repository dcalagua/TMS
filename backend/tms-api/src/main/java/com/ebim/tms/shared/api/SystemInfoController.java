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
 */
@RestController
@RequestMapping("${tms.api.base-path}/system")
@Tag(name = "System", description = "Service identification and liveness")
public class SystemInfoController {

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
    @Operation(summary = "Service name, version, active profiles and server time")
    public SystemInfoResponse info() {
        return new SystemInfoResponse(
                apiProperties.applicationName(),
                resolveVersion(),
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
}
