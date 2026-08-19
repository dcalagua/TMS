package com.ebim.tms.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed application settings under the {@code tms.api} prefix.
 *
 * @param basePath        prefix for every business endpoint, for example {@code /api/v1}
 * @param applicationName public service name reported by the system info endpoint
 */
@Validated
@ConfigurationProperties(prefix = "tms.api")
public record TmsApiProperties(
        @NotBlank @Pattern(regexp = "/.*", message = "must start with '/'") String basePath,
        @NotBlank String applicationName) {
}
