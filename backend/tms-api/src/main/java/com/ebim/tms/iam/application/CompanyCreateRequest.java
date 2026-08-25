package com.ebim.tms.iam.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new company inside the organization the caller is already an administrator of.
 *
 * <p>There is no {@code organizationId}. The organization comes from the resolved
 * {@code CompanyScope} of the request - the caller is signed into a company, and the new one joins
 * that company's organization. A field here would be the one place in the product where a caller
 * names a tenant, which is exactly what ADR-003 forbids.
 *
 * <p>{@code code} is accepted here and nowhere else: it is the tenant key, unique within the
 * organization, and this is the only moment it is chosen. See {@link CompanyProfileRequest} for why
 * it is not editable afterwards.
 */
public record CompanyCreateRequest(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$",
                message = "must be 2 to 32 letters, digits, hyphens or underscores")
        String code,

        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String name,

        @Size(max = 60, message = "must be at most 60 characters")
        String taxIdentifier,

        @NotBlank(message = "is required")
        @Size(max = 60, message = "must be at most 60 characters")
        String timeZone) {
}
