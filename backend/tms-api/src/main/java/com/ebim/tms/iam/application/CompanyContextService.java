package com.ebim.tms.iam.application;

import com.ebim.tms.iam.lifecycle.application.TenantLifecycleService;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Describes the company scope a request is bound to.
 *
 * <p>Takes a {@link CompanyScope}, never a company id: the only way to obtain a scope is
 * server-side membership validation, so this use case cannot be invoked for a company the
 * caller does not belong to. That is the pattern every company-scoped use case in later steps
 * follows - the tenant is a parameter the framework resolved, not one the caller chose.
 */
@Service
public class CompanyContextService {

    /** Optional, as in {@link MeService}: the carrier's name is a label. */
    private final ObjectProvider<CarrierLookupPort> carriers;
    /** Optional, as in {@link MeService}: the operational state is the banner's input. */
    private final ObjectProvider<TenantLifecycleService> lifecycle;

    /** Optional, as in {@link MeService}: the default country is a form default. */
    private final ObjectProvider<CompanySettingsPort> settings;

    public CompanyContextService(ObjectProvider<CarrierLookupPort> carriers,
            ObjectProvider<TenantLifecycleService> lifecycle, ObjectProvider<CompanySettingsPort> settings) {
        this.carriers = carriers;
        this.lifecycle = lifecycle;
        this.settings = settings;
    }

    public CompanyAccessView describe(CompanyScope scope) {
        return CompanyAccessViews.from(scope, carriers.getIfAvailable(),
                OperationalStatuses.of(lifecycle.getIfAvailable(), Set.of(scope.organizationId()))
                        .get(scope.organizationId()), settings.getIfAvailable());
    }
}
