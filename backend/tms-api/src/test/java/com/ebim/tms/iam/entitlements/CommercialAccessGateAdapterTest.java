package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.application.CommercialAccessGateAdapter;
import com.ebim.tms.iam.entitlements.application.CommercialEntitlementService;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.AccessFacts;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.shared.security.CommercialAccessGate;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TestPrincipals;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * D-14 ruling 1 (2026-09-29) at the security chains' port: no commercial fact - {@code appActive=false},
 * no snapshot yet in PRIMARY, any mode - ever refuses a company-scoped request. The commercial surface
 * is withdrawn by {@link CommercialEntitlementService#capabilityEnabled} / {@code limit}; operation
 * continues.
 */
class CommercialAccessGateAdapterTest {

    private final InMemoryEntitlementStore store = new InMemoryEntitlementStore(EnforcementMode.SHADOW);
    private final CommercialAccessGateAdapter gate = new CommercialAccessGateAdapter(
            new CommercialEntitlementService(store, ReceiverProfile.tms()));

    private static CompanyScope scope() {
        return TestPrincipals.planner().companyScope(TestPrincipals.NORTH_LIMA).orElseThrow();
    }

    @ParameterizedTest(name = "under CP={0}, {1}, appActive={2} -> operation allowed")
    @CsvSource(nullValues = "none", value = {
        "true,  PRIMARY,   false",
        "true,  PRIMARY,   none",
        "true,  DUAL_READ, false",
        "true,  DUAL_READ, none",
        "true,  SHADOW,    false",
        "true,  PRIMARY,   true",
        "false, PRIMARY,   none",
    })
    void commercialFactsNeverRefuseOperation(boolean underControlPlane, EnforcementMode mode, Boolean appActive) {
        store.currentCompany = Optional.of(new AccessFacts(underControlPlane, mode, appActive));

        CommercialAccessGate.Verdict verdict = gate.check(scope());

        assertThat(verdict.allowed()).isTrue();
    }
}
