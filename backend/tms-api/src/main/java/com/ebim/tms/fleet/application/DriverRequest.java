package com.ebim.tms.fleet.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Create and update share one shape; see {@code VehicleRequest} for the reasoning behind the
 * lower-case-accepting code pattern and the company coming from context rather than the body.
 *
 * <p>{@code carrierId} is optional (a company may employ its own drivers) and
 * {@code licenseExpiresOn} is too - {@code DriverLicenseStatus} treats a missing expiry as
 * "unrecorded", never as expired, so a fleet migrated without expiry dates stays usable.
 *
 * <p>There is deliberately no {@code active} field. Activation is its own endpoint with its own
 * audit action, exactly as it is for every other master in TMS: retiring a driver is a decision,
 * not a side effect of correcting their phone number.
 */
public record DriverRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 32) String documentType,
        @NotBlank @Size(max = 64) String documentNumber,
        @Size(max = 32) String phone,
        @NotBlank @Size(max = 64) String licenseNumber,
        @Size(max = 32) String licenseCategory,
        LocalDate licenseExpiresOn,
        UUID carrierId) {
}
