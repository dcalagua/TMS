package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.DestinationType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Create and update share one shape; see {@link OriginRequest} for the reasoning behind the
 * lower-case-accepting code pattern and the company/id coming from context rather than the body.
 */
public record DestinationRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull DestinationType type,
        @Size(max = 500) String address,
        @Size(max = 300) String addressReference,
        @Size(max = 120) String district,
        @Size(max = 120) String province,
        @Size(max = 120) String department,
        @NotBlank @Size(max = 60) String country,
        @DecimalMin(value = "-90", message = "must be between -90 and 90")
        @DecimalMax(value = "90", message = "must be between -90 and 90") BigDecimal latitude,
        @DecimalMin(value = "-180", message = "must be between -180 and 180")
        @DecimalMax(value = "180", message = "must be between -180 and 180") BigDecimal longitude,
        UUID zoneId,
        @NotNull @Min(value = 0, message = "must be zero or greater") Integer serviceTimeMinutes,
        @Size(max = 100) String externalReference) {
}
