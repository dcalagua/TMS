package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.application.EntitlementStore.ProvisionedTenant;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The TMS receiver with the TMS profile: an empty sellable registry, so a snapshot with no
 * capabilities is fully APPLIED and any sellable code MasterAdmin might send is stored, reported and
 * never granted.
 */
class TmsEntitlementsReceiverTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4ccc-8000-000000001201");

    private final Receiver receiver = Receiver.tms(EnforcementMode.SHADOW);
    private final ProvisionedTenant tenant = receiver.store.provision(TENANT);

    @Test
    @DisplayName("a baseline TMS snapshot is APPLIED and the GET returns the same version and checksum")
    void appliesAndReadsBack() {
        ObjectNode snapshot = TmsSnapshots.snapshot(TENANT, 1, true);

        Receiver.Response put = receiver.put(TENANT.toString(), snapshot);
        Receiver.Response get = receiver.get(TENANT.toString());

        assertThat(put.status()).isEqualTo(200);
        assertThat(put.body()).containsEntry("status", "APPLIED").containsEntry("replayed", false)
                .containsEntry("appliedVersion", 1L).containsEntry("unknownCapabilities", List.of());
        assertThat(get.body()).containsEntry("productCode", "tms").containsEntry("appliedVersion", 1L)
                .containsEntry("appliedChecksum", snapshot.get("checksum").stringValue())
                .containsEntry("status", "APPLIED").containsEntry("enforcementMode", "SHADOW");
        assertThat(receiver.store.locks).isEqualTo(1);
        assertThat(receiver.store.audit).extracting(InMemoryEntitlementStore.AuditRow::outcome)
                .containsExactly("APPLIED");
    }

    @Test
    @DisplayName("before any snapshot the GET answers NONE, never a guess")
    void getBeforeAnySnapshot() {
        assertThat(receiver.get(TENANT.toString()).body())
                .containsEntry("status", "NONE").containsEntry("appliedVersion", null)
                .containsEntry("appliedChecksum", null).containsEntry("enforcementMode", "SHADOW");
    }

    @Test
    @DisplayName("a sellable code TMS does not know is stored, reported and never granted")
    void unknownSellableIsNeverGranted() {
        ObjectNode snapshot = TmsSnapshots.snapshot(TENANT, 1, true, s -> {
            TmsSnapshots.capability(s, "tms.routing.optimizer", true);
            TmsSnapshots.limit(s, "tms.vehicles.max", 50, "HARD");
        });
        receiver.store.setMode(TENANT.toString(), EnforcementMode.PRIMARY);

        Receiver.Response put = receiver.put(TENANT.toString(), snapshot);

        assertThat(put.body()).containsEntry("status", "APPLIED_WITH_WARNINGS")
                .containsEntry("unknownCapabilities", List.of("tms.routing.optimizer", "tms.vehicles.max"));
        assertThat(receiver.commercial.capabilityEnabled(tenant.organizationId(), "tms.routing.optimizer"))
                .isFalse();
        assertThat(receiver.commercial.limit(tenant.organizationId(), "tms.vehicles.max")).isEmpty();
    }

    @Test
    @DisplayName("a reused jti is 401 JTI_REPLAYED, even for a snapshot that would otherwise replay cleanly")
    void jtiIsSingleUse() {
        byte[] body = TmsSnapshots.snapshot(TENANT, 1, true).toString().getBytes(StandardCharsets.UTF_8);
        JtiGuard.Call call = receiver.token();

        assertThat(receiver.put(TENANT.toString(), body, call).status()).isEqualTo(200);
        Receiver.Response replay = receiver.put(TENANT.toString(), body, call);

        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.body()).containsEntry("error", "JTI_REPLAYED");
        assertThat(receiver.put(TENANT.toString(), body, receiver.token()).body()).containsEntry("replayed", true);
    }

    @Test
    @DisplayName("appActive=false in SHADOW is stored and recorded as a difference, and decides nothing")
    void inactiveInShadowIsOnlyADifference() {
        receiver.put(TENANT.toString(), TmsSnapshots.snapshot(TENANT, 1, false));

        assertThat(receiver.store.diffs).singleElement().satisfies(diff -> {
            assertThat(diff.kind()).isEqualTo("APP_ACTIVE");
            assertThat(diff.mode()).isEqualTo(EnforcementMode.SHADOW);
            assertThat(diff.legacyDecision()).isTrue();
            assertThat(diff.snapshotDecision()).isFalse();
        });
        assertThat(receiver.commercial.access(tenant.organizationId()).allowed()).isTrue();
    }

    @Test
    @DisplayName("a snapshot for another product is SNAPSHOT_INVALID, and an unknown contract header 422")
    void wrongProductAndWrongContract() {
        ObjectNode ewm = TmsSnapshots.snapshot(TENANT, 1, true, s -> s.put("productCode", "ewm"));
        assertThat(receiver.put(TENANT.toString(), ewm).body()).containsEntry("error", "SNAPSHOT_INVALID");

        byte[] body = TmsSnapshots.snapshot(TENANT, 1, true).toString().getBytes(StandardCharsets.UTF_8);
        try {
            receiver.put.apply(TENANT.toString(), body, "entitlements.v2", null, receiver.token());
        } catch (com.ebim.tms.iam.entitlements.domain.EntitlementRejection e) {
            assertThat(e.code().name()).isEqualTo("UNSUPPORTED_CONTRACT_VERSION");
            assertThat(e.code().status()).isEqualTo(422);
            return;
        }
        throw new AssertionError("an unknown contract version must be refused");
    }

    @Test
    @DisplayName("duplicate keys make the document ambiguous and are refused")
    void duplicateKeysAreRefused() {
        String text = TmsSnapshots.snapshot(TENANT, 1, true).toString();
        String duplicated = text.replaceFirst("\"appActive\":true", "\"appActive\":true,\"appActive\":false");

        assertThat(receiver.put(TENANT.toString(), duplicated.getBytes(StandardCharsets.UTF_8), receiver.token())
                .body()).containsEntry("error", "SNAPSHOT_INVALID");
    }
}
