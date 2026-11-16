package com.ebim.tms.iam.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.iam.entitlements.InMemoryEntitlementStore;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.inventory.application.InventoryCursor;
import com.ebim.tms.iam.inventory.application.PlatformTenantInventoryService;
import com.ebim.tms.iam.inventory.domain.InventoryRejection;
import com.ebim.tms.iam.provisioning.infrastructure.InMemoryPlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import com.ebim.tms.iam.provisioning.security.PlatformInventoryProperties;
import com.ebim.tms.iam.provisioning.security.PlatformScopes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;

/** The configuration and pure rules of {@code tenant-inventory.v1}, without Spring. */
class PlatformTenantInventoryServiceTest {

    private static final PlatformCaller CALLER = new PlatformCaller("masteradmin-provisioning", "jti", null, null);
    private static int jtis;

    private static PlatformTenantInventoryService service(PlatformInventoryProperties scopes, String environment,
            InMemoryPlatformProvisioningRepository repository) {
        return new PlatformTenantInventoryService(new InMemoryTenantInventoryStore(repository),
                new JtiGuard(new InMemoryEntitlementStore(EnforcementMode.SHADOW)), scopes,
                new PlatformEntitlementsProperties(environment), ReceiverProfile.tms(),
                Mockito.mock(PlatformTransactionManager.class));
    }

    private static JtiGuard.Call call() {
        return new JtiGuard.Call("masteradmin.ebim", "masteradmin-provisioning", "unit-" + (++jtis),
                Instant.now().plusSeconds(300));
    }

    private static PlatformInventoryProperties configured() {
        return new PlatformInventoryProperties("tms:tenants:list", "tms:tenants:link");
    }

    @Test
    @DisplayName("unset scope or environment closes the route: 503 *_NOT_CONFIGURED, before the jti is spent")
    void failsClosed() {
        InMemoryPlatformProvisioningRepository repository = new InMemoryPlatformProvisioningRepository();
        for (PlatformTenantInventoryService closed : new PlatformTenantInventoryService[] {
                service(PlatformInventoryProperties.unset(), "DEV", repository),
                service(configured(), "", repository),
                service(configured(), "STAGING", repository)}) {
            assertThat(closed.listConfigured()).isFalse();
            assertThat(closed.linkConfigured()).isFalse();
            assertThatThrownBy(() -> closed.list(null, null, null, null, CALLER, call()))
                    .isInstanceOfSatisfying(InventoryRejection.class, rejection -> {
                        assertThat(rejection.code()).isEqualTo(InventoryRejection.Code.INVENTORY_NOT_CONFIGURED);
                        assertThat(rejection.code().status()).isEqualTo(503);
                    });
            assertThatThrownBy(() -> closed.link(UUID.randomUUID().toString(), new byte[0], true, null, null, CALLER,
                    call()))
                    .isInstanceOfSatisfying(InventoryRejection.class, rejection -> assertThat(rejection.code())
                            .isEqualTo(InventoryRejection.Code.TENANT_LINK_NOT_CONFIGURED));
        }
        assertThat(repository.audit).isEmpty();
    }

    @Test
    @DisplayName("each route opens on its own scope")
    void independentRoutes() {
        InMemoryPlatformProvisioningRepository repository = new InMemoryPlatformProvisioningRepository();
        PlatformTenantInventoryService listOnly =
                service(new PlatformInventoryProperties("tms:tenants:list", null), "QAS", repository);
        assertThat(listOnly.listConfigured()).isTrue();
        assertThat(listOnly.linkConfigured()).isFalse();
        Map<String, Object> body = listOnly.list(null, null, "tenant-inventory.v1", null, CALLER, call());
        assertThat(body).containsEntry("contract", "tenant-inventory.v1").containsEntry("productCode", "tms")
                .containsEntry("environment", "QAS").containsEntry("nextCursor", null);
    }

    @Test
    @DisplayName("a scope that is blank, multi-valued, a fixed TMS scope, or shared by both routes is not used")
    void unusableScopes() {
        assertThat(PlatformInventoryProperties.unset().problems()).isEmpty();
        assertThat(new PlatformInventoryProperties("  ", "\t").listScope()).isEmpty();
        assertThat(new PlatformInventoryProperties(" tms:tenants:list ", null).listScope()).contains("tms:tenants:list");

        PlatformInventoryProperties reused =
                new PlatformInventoryProperties(PlatformScopes.TENANT_READ, PlatformScopes.TENANT_CREATE);
        assertThat(reused.listScope()).isEmpty();
        assertThat(reused.adoptScope()).isEmpty();
        assertThat(reused.problems()).hasSize(2).allMatch(problem -> problem.contains("fixed TMS scope"));

        PlatformInventoryProperties same = new PlatformInventoryProperties("tms:tenants:all", "tms:tenants:all");
        assertThat(same.listScope()).isEmpty();
        assertThat(same.adoptScope()).isEmpty();

        PlatformInventoryProperties two = new PlatformInventoryProperties("tms:tenants:list tms:tenants:link", null);
        assertThat(two.listScope()).isEmpty();
        assertThat(two.problems()).singleElement().asString().contains("TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE");
    }

    @Test
    @DisplayName("the cursor round-trips and refuses anything it did not write")
    void cursor() {
        UUID id = UUID.randomUUID();
        String cursor = InventoryCursor.encode(id);
        assertThat(cursor).doesNotContain(id.toString()).matches("[A-Za-z0-9_-]+");
        assertThat(InventoryCursor.decode(cursor)).contains(id);
        assertThat(InventoryCursor.decode(null)).isEmpty();
        assertThat(InventoryCursor.decode("")).isEmpty();
        assertThat(InventoryCursor.decode("%%%")).isEmpty();
        assertThat(InventoryCursor.decode(id.toString())).isEmpty();
        assertThat(InventoryCursor.decode(java.util.Base64.getUrlEncoder().encodeToString("o1:1-1-1-1-1".getBytes())))
                .isEmpty();
        assertThat(InventoryCursor.decode("x".repeat(101))).isEmpty();
    }
}
