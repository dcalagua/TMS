package com.ebim.tms.platform.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The provisioning request as EBIM MasterAdmin sends it.
 *
 * <p>It is transcribed from the control-plane contract rather than from the TMS schema, which is
 * why several fields have nowhere to go: TMS models no legal name, country code or currency on an
 * organization or a company. Those fields are accepted and validated for shape, and then dropped -
 * they are not written to invented columns, and they are not silently renamed into columns that
 * mean something else. {@code docs/platform-provisioning/DESIGN.md} lists every field and its fate.
 *
 * <p>Two of them do travel, just not where the contract puts them: the timezone and the tax
 * identifier live on the <em>company</em> in TMS, because that is where the columns are.
 */
public record TenantProvisioningRequest(

        @NotNull(message = "controlPlaneTenantId is required")
        UUID controlPlaneTenantId,

        @NotNull(message = "organization is required")
        @Valid
        Organization organization,

        @NotNull(message = "company is required")
        @Valid
        Company company,

        /** Optional: a tenant with no administrator is one nobody can sign in to yet. */
        @Valid
        Admin admin,

        @NotBlank(message = "deploymentMode is required")
        String deploymentMode) {

    /**
     * @param slug becomes {@code tms.organization.code} after normalisation. TMS codes are
     *     upper-case and at most 32 characters, so a slug that cannot be normalised is refused
     *     rather than truncated into something that is nearly right
     */
    public record Organization(
            @NotNull(message = "organization.id is required") UUID id,
            @NotBlank(message = "organization.slug is required") @Size(max = 64) String slug,
            @NotBlank(message = "organization.name is required") @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @Size(max = 64) String taxId,
            @Size(max = 2) String countryCode,
            @Size(max = 3) String currency,
            @Size(max = 64) String timezone) {
    }

    public record Company(
            @NotNull(message = "company.id is required") UUID id,
            @NotBlank(message = "company.name is required") @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @Size(max = 64) String taxId,
            @Size(max = 2) String countryCode,
            @Size(max = 3) String currency) {
    }

    public record Admin(
            @NotBlank(message = "admin.email is required")
            @Email(message = "admin.email is not an email address")
            @Size(max = 200) String email,

            @NotBlank(message = "admin.fullName is required")
            @Size(max = 200) String fullName) {
    }
}
