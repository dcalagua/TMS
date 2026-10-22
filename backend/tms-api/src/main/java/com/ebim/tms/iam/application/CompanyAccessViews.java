package com.ebim.tms.iam.application;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.security.Capability;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.util.Optional;
import java.util.Set;

/** Projection of a resolved {@link CompanyScope} into the shape the API publishes. */
final class CompanyAccessViews {

    private CompanyAccessViews() {}

    static CompanyAccessView from(CompanyScope scope, CarrierLookupPort carriers, OperationalStatusView status,
            CompanySettingsPort settings) {
        String carrierName = scope.carrierId() == null || carriers == null ? null
                : Optional.ofNullable(carriers.findAllInCompany(Set.of(scope.carrierId()),
                        scope.companyId()).get(scope.carrierId())).map(MasterReference::name).orElse(null);
        return new CompanyAccessView(
                scope.companyId(),
                scope.companyCode(),
                scope.companyName(),
                scope.timeZone(),
                defaultCountryOf(scope, settings),
                new OrganizationView(scope.organizationId(), scope.organizationCode(), scope.organizationName()),
                scope.permissions().stream().map(Permission::code).sorted().toList(),
                Capability.from(scope.permissions()).stream().map(Enum::name).toList(),
                scope.carrierId(),
                carrierName,
                status == null ? OperationalStatusView.ACTIVE : status);
    }

    /**
     * A label for the forms, so as forgiving as the carrier's name above: a context without the
     * settings port (or a row that is missing) answers the product default rather than failing
     * {@code /me}. One primary-key read per company the caller belongs to.
     */
    private static String defaultCountryOf(CompanyScope scope, CompanySettingsPort settings) {
        CompanySettings read = settings == null ? null : settings.settingsOf(scope.companyId());
        return read == null ? CompanySettings.DEFAULT_COUNTRY : read.defaultCountry();
    }
}
