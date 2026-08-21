package com.ebim.tms.iam.application;

import com.ebim.tms.iam.domain.RoleCatalogueRow;
import java.util.List;

/**
 * One role of the system catalogue, as the access screen offers it.
 *
 * <p>{@code permissionCodes} travels with the role so the screen can show what is about to be
 * granted. That is not a disclosure problem - the catalogue is the same for every installation and
 * is published in {@code docs/security/AUTHORIZATION_MODEL.md} - and it is the difference between
 * an administrator picking "PLANNER" because it sounds right and picking it because they can see it
 * carries {@code orders.order:manage}.
 *
 * @param assignable whether this role may be attached to a company-scoped membership. An
 *     {@code ORGANIZATION} role attached to one grants nothing at all, because the identity query
 *     discards that pairing - so the picker shows it greyed with the reason rather than letting an
 *     administrator save a grant that silently does nothing. {@code UserAdministrationService}
 *     refuses it server-side too.
 */
public record RoleView(
        String code,
        String name,
        String description,
        String scopeLevel,
        boolean assignable,
        List<String> permissionCodes) {

    public static RoleView from(RoleCatalogueRow row) {
        return new RoleView(
                row.code(),
                row.name(),
                row.description(),
                row.scopeLevel(),
                row.assignableToCompanyMembership(),
                row.permissionCodes());
    }
}
