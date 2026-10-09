package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.domain.CommercialAccess;
import com.ebim.tms.shared.security.CommercialAccessGate;
import com.ebim.tms.shared.security.CompanyScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers the security chains' {@link CommercialAccessGate} from the local MasterAdmin snapshot.
 *
 * <p>The facts are read for the company the connection is scoped to (V56's
 * {@code tms.commercial_access_current_company()}), which is {@code scope}'s by construction: the
 * filter runs after the scope is bound, on the same request. {@code scope} is used only for the
 * warnings.
 *
 * <p>D-14 ruling 1 (2026-09-29): a commercial fact never refuses a request. {@code appActive=false} or
 * PRIMARY without a snapshot withdraw the commercial surface ({@link CommercialEntitlementService}'s
 * {@code capabilityEnabled}/{@code limit}); the verdict follows {@link CommercialAccess#operationAllowed()},
 * which is the single place a future operational-shutdown policy would change.
 */
@Component
public class CommercialAccessGateAdapter implements CommercialAccessGate {

    private static final Logger log = LoggerFactory.getLogger(CommercialAccessGateAdapter.class);

    private final CommercialEntitlementService entitlements;

    public CommercialAccessGateAdapter(CommercialEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public Verdict check(CompanyScope scope) {
        CommercialAccess access = entitlements.accessForCurrentCompany();
        if (access.reason() == CommercialAccess.Reason.NO_SNAPSHOT_LEGACY_FALLBACK) {
            log.warn("Entitlements DUAL_READ with no applied snapshot for organization {}: legacy decides",
                    scope.organizationId());
        }
        if (!access.commercialActive()) {
            log.debug("Commercial surface withdrawn ({}, {}) for organization {}; operation continues",
                    access.reason(), access.mode(), scope.organizationId());
        }
        return access.operationAllowed() ? Verdict.ALLOWED : Verdict.suspended(access.reason().name());
    }
}
