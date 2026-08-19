package com.ebim.tms.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed application settings under the {@code tms.api} prefix.
 *
 * @param basePath            prefix for every business endpoint, for example {@code /api/v1}
 * @param applicationName     public service name reported by the system info endpoint
 * @param publicDocumentation whether the OpenAPI description and Swagger UI may be read without
 *     authentication. True in development, where it is how the contract is explored; false in
 *     production, where publishing the entire API surface to anonymous callers is free
 *     reconnaissance
 */
@Validated
@ConfigurationProperties(prefix = "tms.api")
public record TmsApiProperties(
        @NotBlank @Pattern(regexp = "/.*", message = "must start with '/'") String basePath,
        @NotBlank String applicationName,
        Boolean publicDocumentation) {

    public TmsApiProperties {
        publicDocumentation = publicDocumentation == null || publicDocumentation;
    }
}
