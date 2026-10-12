package com.ebim.tms.iam.application;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
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

    public CompanyContextService(ObjectProvider<CarrierLookupPort> carriers) {
        this.carriers = carriers;
    }

    public CompanyAccessView describe(CompanyScope scope) {
        return CompanyAccessViews.from(scope, carriers.getIfAvailable());
    }
}
