package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Create and update share one shape; see {@link OriginRequest} for the reasoning behind the
 * lower-case-accepting code pattern and the company/id coming from context rather than the body.
 *
 * <p>{@code roles} is {@code @NotEmpty} on purpose: a location that may do nothing is not a
 * business record, it is a typo. {@code externalSystem}/{@code externalReference} must be
 * supplied together - a reference with no system cannot be deduplicated, which is the whole
 * point of carrying it (see {@code ck_location_external_pair_complete} in V14).
 */
public record LocationRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull LocationType type,
        @NotEmpty(message = "a location must hold at least one role") Set<LocationRole> roles,
        @Size(max = 500) String address,
        @Size(max = 300) String addressReference,
        @Size(max = 120) String district,
        @Size(max = 120) String province,
        @Size(max = 120) String department,
        @NotBlank @Size(max = 60) String country,
        @NotBlank @Size(max = 64) String timeZone,
        @DecimalMin(value = "-90", message = "must be between -90 and 90")
        @DecimalMax(value = "90", message = "must be between -90 and 90") BigDecimal latitude,
        @DecimalMin(value = "-180", message = "must be between -180 and 180")
        @DecimalMax(value = "180", message = "must be between -180 and 180") BigDecimal longitude,
        UUID zoneId,
        @NotNull @Min(value = 0, message = "must be zero or greater") Integer serviceTimeMinutes,
        @Size(max = 60) String externalSystem,
        @Size(max = 100) String externalReference) {
}
