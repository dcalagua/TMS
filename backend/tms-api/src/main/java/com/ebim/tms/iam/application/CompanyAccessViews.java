package com.ebim.tms.iam.application;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.security.Capability;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.util.Optional;
import java.util.Set;

/** Projection of a resolved {@link CompanyScope} into the shape the API publishes. */
final class CompanyAccessViews {

    private CompanyAccessViews() {}

    static CompanyAccessView from(CompanyScope scope, CarrierLookupPort carriers) {
        String carrierName = scope.carrierId() == null || carriers == null ? null
                : Optional.ofNullable(carriers.findAllInCompany(Set.of(scope.carrierId()),
                        scope.companyId()).get(scope.carrierId())).map(MasterReference::name).orElse(null);
        return new CompanyAccessView(
                scope.companyId(),
                scope.companyCode(),
                scope.companyName(),
                scope.timeZone(),
                new OrganizationView(scope.organizationId(), scope.organizationCode(), scope.organizationName()),
                scope.permissions().stream().map(Permission::code).sorted().toList(),
                Capability.from(scope.permissions()).stream().map(Enum::name).toList(),
                scope.carrierId(),
                carrierName);
    }
}
