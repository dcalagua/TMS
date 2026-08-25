package com.ebim.tms.iam.domain;

import java.util.List;
import java.util.UUID;

/**
 * One role of the system catalogue ({@code tms.role}, seeded by migration V3).
 *
 * @param scopeLevel {@code ORGANIZATION} or {@code COMPANY}. This is not decoration: an
 *     {@code ORGANIZATION} role attached to a company-scoped membership grants <em>nothing</em>,
 *     because {@code JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL} discards that pairing
 *     ({@code m.company_id IS NULL OR r.scope_level <> 'ORGANIZATION'}). The database cannot
 *     express the rule, so the administration service refuses the assignment rather than letting an
 *     administrator save a role that would silently do nothing.
 * @param permissionCodes what the role actually grants, so the screen can answer "what am I about
 *     to give this person" without a second round trip per role
 */
public record RoleCatalogueRow(
        UUID id,
        String code,
        String name,
        String description,
        String scopeLevel,
        List<String> permissionCodes) {

    public static final String SCOPE_COMPANY = "COMPANY";

    /** Whether this role can be attached to a company-scoped membership and mean something. */
    public boolean assignableToCompanyMembership() {
        return SCOPE_COMPANY.equals(scopeLevel);
    }
}
