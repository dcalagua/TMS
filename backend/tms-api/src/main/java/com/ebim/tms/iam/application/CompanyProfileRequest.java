package com.ebim.tms.iam.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a company administrator may change about their own company, profile and settings in one
 * request.
 *
 * <p>Four things are deliberately <em>not</em> here:
 *
 * <ul>
 *   <li>{@code id} - the company is the resolved {@code CompanyScope}, never a field. That is the
 *       whole of the tenancy guarantee for this endpoint: there is no company id in the request
 *       body to point somewhere else.</li>
 *   <li>{@code code} - the tenant key. Business rows do not carry it, so renaming it breaks
 *       nothing in the database, but it is what appears in the company switcher, in export
 *       filenames and in every screenshot an operator has ever taken. It is set once, at creation.</li>
 *   <li>{@code organizationId} - moving a company between organizations would silently rewrite who
 *       may act in it, because memberships are keyed on the pair. It is not an edit; it is a
 *       migration.</li>
 *   <li>{@code active} - deactivating the company you are signed in to would end your own session
 *       and leave nobody able to undo it from the UI. See {@code CompanyAdministrationService}.</li>
 * </ul>
 *
 * <p>The regular expressions mirror the CHECK constraints of migration V34 exactly, so a value this
 * record accepts is a value the database accepts. They are here as well as there because a
 * constraint violation surfaces as a 500 with a constraint name, and a person typing a prefix
 * deserves a sentence.
 */
public record CompanyProfileRequest(
        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String name,

        @Size(max = 60, message = "must be at most 60 characters")
        String taxIdentifier,

        // An IANA zone id. Checked against the JVM's zone database by the service rather than by an
        // annotation: a CHECK constraint cannot query pg_timezone_names, which is why V2 left this
        // "validated in Java", and a regular expression that accepted "America/Lma" would be worse
        // than no pattern at all.
        @NotBlank(message = "is required")
        @Size(max = 60, message = "must be at most 60 characters")
        String timeZone,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter ISO country code, for example PE")
        String defaultCountry,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{0,5}-$",
                message = "must be one to six letters or digits followed by a hyphen, for example TO-")
        String orderNumberPrefix,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{0,5}-$",
                message = "must be one to six letters or digits followed by a hyphen, for example SH-")
        String shipmentNumberPrefix) {
}
