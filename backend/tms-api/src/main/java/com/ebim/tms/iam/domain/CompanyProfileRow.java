package com.ebim.tms.iam.domain;

import com.ebim.tms.shared.settings.CompanySettings;
import java.util.UUID;

/**
 * One company as its administration screen reads it: the tenant row, the organization above it and
 * the operational defaults beside it (migration V34).
 *
 * <p>{@code settings} is never null. The row in {@code tms.company_settings} legitimately may not
 * exist - V34 section 4 - and the repository resolves that to {@link CompanySettings#defaults()},
 * so the screen shows the values the product is actually using rather than four empty inputs that
 * would save as blanks.
 *
 * @param organizationActive whether the organization above this company is itself active. Read
 *     because a deactivated organization revokes every membership below it
 *     ({@code JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL}), so it is the answer to "why did
 *     everybody lose access at once" - and it is not something a company administrator can fix
 *     from this screen.
 */
public record CompanyProfileRow(
        UUID id,
        String code,
        String name,
        String taxIdentifier,
        String timeZone,
        boolean active,
        UUID organizationId,
        String organizationCode,
        String organizationName,
        boolean organizationActive,
        CompanySettings settings) {}
