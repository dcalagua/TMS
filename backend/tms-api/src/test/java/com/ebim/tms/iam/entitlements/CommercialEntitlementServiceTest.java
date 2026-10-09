package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.application.EntitlementStore.AccessFacts;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.ProvisionedTenant;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.CommercialAccess;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The commercial entitlement service by tenant, fed only by the local snapshot.
 *
 * <p>TMS registers no sellable today, so the capability and limit paths are exercised with a
 * receiver profile that knows two illustrative codes - the same service, a different registry - to
 * prove they are enforced the day one is registered and fail closed until then.
 */
class CommercialEntitlementServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4ccc-8000-000000001241");
    private static final String FEATURE = "tms.example.feature";
    private static final String LIMIT = "tms.example.vehicles";

    private final Receiver receiver = new Receiver(new ReceiverProfile("tms", Set.of("tms.core", FEATURE, LIMIT)),
            "DEV", EnforcementMode.SHADOW);
    private final ProvisionedTenant tenant = receiver.store.provision(TENANT);

    private void apply(long version, boolean appActive, boolean featureEnabled) {
        receiver.put(TENANT.toString(), TmsSnapshots.snapshot(TENANT, version, appActive, s -> {
            TmsSnapshots.capability(s, FEATURE, featureEnabled);
            TmsSnapshots.limit(s, LIMIT, 40, "HARD");
        }));
    }

    @Test
    @DisplayName("in SHADOW nothing sellable is granted from the snapshot and access is not enforced")
    void shadowDecidesNothing() {
        apply(1, false, true);

        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isFalse();
        assertThat(receiver.commercial.limit(tenant.organizationId(), LIMIT)).isEmpty();
        assertThat(receiver.commercial.access(tenant.organizationId()).reason())
                .isEqualTo(CommercialAccess.Reason.NOT_ENFORCED);
    }

    @Test
    @DisplayName("in PRIMARY the snapshot grants the capability and the limit, and revokes them on the next version")
    void primaryFollowsTheSnapshot() {
        receiver.store.setMode(TENANT.toString(), EnforcementMode.PRIMARY);
        apply(1, true, true);

        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isTrue();
        assertThat(receiver.commercial.limit(tenant.organizationId(), LIMIT)).get()
                .satisfies(l -> {
                    assertThat(l.value()).isEqualTo(40.0);
                    assertThat(l.enforcement()).isEqualTo("HARD");
                });

        apply(2, true, false);
        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isFalse();
    }

    @ParameterizedTest(name = "{0}: appActive=false withdraws every sellable and limit; appActive=true restores them")
    @EnumSource(value = EnforcementMode.class, names = {"DUAL_READ", "PRIMARY"})
    @DisplayName("D-14 ruling 1: appActive=false turns the commercial surface off, whatever the snapshot grants")
    void inactiveAppWithdrawsTheCommercialSurface(EnforcementMode mode) {
        receiver.store.setMode(TENANT.toString(), mode);
        apply(1, false, true);

        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isFalse();
        assertThat(receiver.commercial.limit(tenant.organizationId(), LIMIT)).isEmpty();

        apply(2, true, true);
        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isTrue();
        assertThat(receiver.commercial.limit(tenant.organizationId(), LIMIT)).isPresent();
    }

    @Test
    @DisplayName("a grant scoped to explicit MasterAdmin company ids reaches nobody in TMS (fail closed)")
    void explicitCompaniesAreNotGranted() {
        receiver.store.setMode(TENANT.toString(), EnforcementMode.PRIMARY);
        receiver.put(TENANT.toString(), TmsSnapshots.snapshot(TENANT, 1, true, s -> {
            TmsSnapshots.capability(s, FEATURE, true);
            ObjectNode scope = (ObjectNode) s.get("capabilities").get(0).get("scope");
            scope.put("level", "COMPANY");
            ((ArrayNode) scope.putArray("companyIds")).add("00000000-0000-4ccc-8000-00000000c041");
        }));

        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isFalse();
    }

    @Test
    @DisplayName("a code the receiver does not know is never granted, whatever the snapshot says")
    void unknownCodeFailsClosed() {
        receiver.store.setMode(TENANT.toString(), EnforcementMode.PRIMARY);
        receiver.put(TENANT.toString(), TmsSnapshots.snapshot(TENANT, 1, true,
                s -> TmsSnapshots.capability(s, "tms.not.registered", true)));

        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), "tms.not.registered")).isFalse();
    }

    @Test
    @DisplayName("MasterAdmin unreachable: the last applied snapshot keeps deciding, without expiry")
    void offlineLastGood() {
        receiver.store.setMode(TENANT.toString(), EnforcementMode.PRIMARY);
        apply(1, false, true);

        // No client to MasterAdmin exists in TMS: the decision is a function of local state only, so
        // "MasterAdmin is down" is simply "no new PUT arrives". The commercial withdrawal stays in
        // force, and operation continues throughout (D-14 ruling 1).
        for (int i = 0; i < 3; i++) {
            CommercialAccess access = receiver.commercial.access(tenant.organizationId());
            assertThat(access.commercialActive()).isFalse();
            assertThat(access.reason()).isEqualTo(CommercialAccess.Reason.APP_INACTIVE);
            assertThat(access.operationAllowed()).isTrue();
            assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isFalse();
        }
        apply(2, true, true);
        assertThat(receiver.commercial.access(tenant.organizationId()).commercialActive()).isTrue();
        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isTrue();
    }

    @Test
    @DisplayName("PRIMARY before the first snapshot: nothing commercial is granted, and the organization operates")
    void primaryWithoutSnapshotStillOperates() {
        receiver.store.setMode(TENANT.toString(), EnforcementMode.PRIMARY);

        CommercialAccess access = receiver.commercial.access(tenant.organizationId());
        assertThat(access.reason()).isEqualTo(CommercialAccess.Reason.NO_SNAPSHOT);
        assertThat(access.commercialActive()).isFalse();
        assertThat(access.operationAllowed()).isTrue();
        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), FEATURE)).isFalse();
        assertThat(receiver.commercial.limit(tenant.organizationId(), LIMIT)).isEmpty();
    }

    @Test
    @DisplayName("PRODUCT-scope PRIMARY leaves an organization MasterAdmin never provisioned exactly as legacy")
    void productPrimaryDoesNotReachUnmappedOrganizations() {
        receiver.store.setMode(InMemoryEntitlementStore.PRODUCT_SCOPE, EnforcementMode.PRIMARY);
        UUID legacyOrganization = UUID.fromString("00000000-0000-4ccc-8000-0000000012aa");

        CommercialAccess access = receiver.commercial.access(legacyOrganization);
        assertThat(access.reason()).isEqualTo(CommercialAccess.Reason.NOT_UNDER_CONTROL_PLANE);
        assertThat(access.commercialActive()).isTrue();
        assertThat(access.operationAllowed()).isTrue();
    }

    @Test
    @DisplayName("access for the scoped company is decided from the facts V56 returns to the runtime role")
    void currentCompanyAccess() {
        receiver.store.currentCompany = Optional.of(new AccessFacts(true, EnforcementMode.PRIMARY, false));
        CommercialAccess inactive = receiver.commercial.accessForCurrentCompany();
        assertThat(inactive.reason()).isEqualTo(CommercialAccess.Reason.APP_INACTIVE);
        assertThat(inactive.commercialActive()).isFalse();
        assertThat(inactive.operationAllowed()).isTrue();

        receiver.store.currentCompany = Optional.of(new AccessFacts(false, EnforcementMode.PRIMARY, null));
        assertThat(receiver.commercial.accessForCurrentCompany().commercialActive()).isTrue();

        receiver.store.currentCompany = Optional.empty();
        assertThat(receiver.commercial.accessForCurrentCompany().reason())
                .isEqualTo(CommercialAccess.Reason.NOT_UNDER_CONTROL_PLANE);
    }
}
