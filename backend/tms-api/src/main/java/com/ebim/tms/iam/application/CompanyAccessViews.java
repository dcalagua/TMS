package com.ebim.tms.iam.application;

import com.ebim.tms.shared.security.Capability;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;

/** Projection of a resolved {@link CompanyScope} into the shape the API publishes. */
final class CompanyAccessViews {

    private CompanyAccessViews() {}

    static CompanyAccessView from(CompanyScope scope) {
        return new CompanyAccessView(
                scope.companyId(),
                scope.companyCode(),
                scope.companyName(),
                scope.timeZone(),
                new OrganizationView(scope.organizationId(), scope.organizationCode(), scope.organizationName()),
                scope.permissions().stream().map(Permission::code).sorted().toList(),
                Capability.from(scope.permissions()).stream().map(Enum::name).toList());
    }
}
