package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * What {@code iam} needs in order to create a tenant, expressed in TMS terms rather than the
 * control plane's.
 *
 * <p>Everything here maps to a column that exists. The control-plane request carries more than
 * this - a legal name, a country code, a currency - and TMS models none of those, so they are not
 * smuggled in as strings nobody reads. What the mapping drops, and why, is written down in
 * {@code docs/platform-provisioning/DESIGN.md}.
 *
 * @param organizationId the id to create the organization with. Supplied by the caller rather than
 *     generated, so the control plane and TMS agree on it from the first write
 * @param organizationCode normalised to {@code ^[A-Z0-9][A-Z0-9_-]{1,31}$} before it gets here
 * @param companyTimeZone an IANA zone; the column is {@code NOT NULL} and defaults to {@code UTC}
 * @param adminEmail may be null: a tenant with no administrator is a tenant nobody can sign in to
 *     yet, which is a legitimate thing for a control plane to create
 */
public record TenantOnboardingCommand(
        UUID organizationId,
        String organizationCode,
        String organizationName,
        UUID companyId,
        String companyCode,
        String companyName,
        String companyTaxIdentifier,
        String companyTimeZone,
        String adminEmail,
        String adminFullName) {

    public boolean hasAdmin() {
        return adminEmail != null && !adminEmail.isBlank();
    }
}
