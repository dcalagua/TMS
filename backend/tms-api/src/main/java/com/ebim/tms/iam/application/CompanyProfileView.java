package com.ebim.tms.iam.application;

import com.ebim.tms.iam.domain.CompanyProfileRow;
import java.util.UUID;

/**
 * One company as its administrator sees it: the tenant record, the organization above it, and the
 * operational defaults beside it.
 *
 * <p>Distinct from {@link CompanyAccessView}, which answers a different question. That one says
 * "what may <em>I</em> do here" and is read by the shell on every sign-in; this one says "what
 * <em>is</em> this company" and is read by one screen. Keeping them apart is what lets the
 * settings live off the authentication hot path (V34 section 1).
 *
 * @param organizationActive whether the organization above this company is active. Shown, and not
 *     editable here: a deactivated organization revokes every membership beneath it, so an
 *     administrator staring at a company nobody can reach deserves to see the reason rather than
 *     conclude the company itself is broken. Fixing it is an {@code iam.organization:manage} act.
 * @param canCreateCompany whether this caller may add a company to the organization - that is,
 *     whether they hold an active organization-wide membership in it. It travels in the response
 *     because it is not derivable from any permission the shell already has: {@code
 *     iam.company:manage} is held by a COMPANY_ADMIN too, and only the membership's shape tells the
 *     two apart. UX only, like every other flag the frontend receives; {@code
 *     CompanyAdministrationService.create} re-asks the database and refuses with 403 regardless of
 *     what the browser decided to render.
 */
public record CompanyProfileView(
        UUID id,
        String code,
        String name,
        String taxIdentifier,
        String timeZone,
        boolean active,
        OrganizationView organization,
        boolean organizationActive,
        boolean canCreateCompany,
        CompanySettingsView settings) {

    public static CompanyProfileView from(CompanyProfileRow row, boolean canCreateCompany) {
        return new CompanyProfileView(
                row.id(),
                row.code(),
                row.name(),
                row.taxIdentifier(),
                row.timeZone(),
                row.active(),
                new OrganizationView(row.organizationId(), row.organizationCode(), row.organizationName()),
                row.organizationActive(),
                canCreateCompany,
                CompanySettingsView.from(row.settings()));
    }
}
