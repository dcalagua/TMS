package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.OriginType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Create and update share one shape: every field is editable and neither operation accepts a
 * company id or an origin id from the body - the company comes from the resolved
 * {@link com.ebim.tms.shared.security.CompanyScope} and the id from the path.
 *
 * <p>The {@code code} pattern accepts lower case on input because {@code OriginService}
 * normalizes (trims, upper-cases) before persisting; validating the pre-normalization shape
 * here means a malformed code is rejected with a field-level 400 instead of surfacing as a
 * database check-constraint failure translated into a generic conflict.
 */
public record OriginRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull OriginType type,
        @Size(max = 500) String address,
        @DecimalMin(value = "-90", message = "must be between -90 and 90")
        @DecimalMax(value = "90", message = "must be between -90 and 90") BigDecimal latitude,
        @DecimalMin(value = "-180", message = "must be between -180 and 180")
        @DecimalMax(value = "180", message = "must be between -180 and 180") BigDecimal longitude,
        @NotBlank @Size(max = 64) String timeZone,
        @Size(max = 100) String externalReference) {
}
