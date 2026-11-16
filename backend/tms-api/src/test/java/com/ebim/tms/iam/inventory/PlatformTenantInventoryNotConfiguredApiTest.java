package com.ebim.tms.iam.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsConfig;
import com.ebim.tms.iam.inventory.api.PlatformTenantInventoryController;
import com.ebim.tms.iam.inventory.application.PlatformTenantInventoryService;
import com.ebim.tms.iam.provisioning.api.PlatformProvisioningController;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.security.PlatformInventoryAccess;
import com.ebim.tms.iam.provisioning.security.PlatformM2mTestTokens;
import com.ebim.tms.iam.provisioning.security.PlatformProvisioningSecurityConfig;
import com.ebim.tms.iam.provisioning.security.PlatformScopes;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.security.PublicApiPaths;
import com.ebim.tms.shared.security.SecurityConfig;
import com.ebim.tms.shared.security.SecurityTestConfiguration;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.security.TmsSecurityProperties;
import com.ebim.tms.shared.web.WebConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The routes of {@code tenant-inventory.v1} with their scopes NOT usable: the list scope reuses the fixed
 * create scope (refused, logged) and the link scope is unset. Both routes are closed - {@code 503} to every
 * authenticated caller, whatever its scopes - and still {@code 401} without a token.
 */
@WebMvcTest(controllers = {PlatformTenantInventoryController.class, PlatformProvisioningController.class})
@Import({
    ApplicationConfig.class,
    SecurityConfig.class,
    PublicApiPaths.class,
    TmsJwtAuthenticationConverter.class,
    TmsAuthenticationEntryPoint.class,
    TmsAccessDeniedHandler.class,
    ApiExceptionResponder.class,
    ApiExceptionHandler.class,
    WebConfig.class,
    SecurityTestConfiguration.class,
    PlatformProvisioningSecurityConfig.class,
    PlatformProvisioningService.class,
    PlatformTenantProvisioner.class,
    PlatformEntitlementsConfig.class,
    JtiGuard.class,
    PlatformInventoryAccess.class,
    PlatformTenantInventoryService.class,
    PlatformTenantInventoryApiTest.Fakes.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class PlatformTenantInventoryNotConfiguredApiTest {

    private static final String TENANTS = "/internal/platform-provisioning/tenants";

    @DynamicPropertySource
    static void enable(DynamicPropertyRegistry registry) {
        registry.add("tms.platform-provisioning.enabled", () -> "true");
        registry.add("tms.platform-provisioning.public-key", PlatformM2mTestTokens::publicKeyPem);
        registry.add("tms.platform-entitlements.environment", () -> "DEV");
        registry.add("tms.platform-inventory.read-scope", () -> PlatformScopes.TENANT_CREATE);
        registry.add("tms.platform-inventory.link-scope", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTenantInventoryStore store;

    @Test
    @DisplayName("GET /tenants is 503 INVENTORY_NOT_CONFIGURED for any MasterAdmin token, 401 without one")
    void listClosed() throws Exception {
        for (String scope : new String[] {PlatformScopes.TENANT_CREATE, PlatformScopes.TENANT_READ,
                PlatformTenantInventoryApiTest.LIST_SCOPE}) {
            mockMvc.perform(get(TENANTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(scope)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("INVENTORY_NOT_CONFIGURED"))
                    .andExpect(jsonPath("$.items").doesNotExist());
        }
        mockMvc.perform(get(TENANTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("PUT /tenants/{id}/link is 503 TENANT_LINK_NOT_CONFIGURED and writes nothing")
    void linkClosed() throws Exception {
        mockMvc.perform(put(TENANTS + "/" + UUID.randomUUID() + "/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(
                                PlatformTenantInventoryApiTest.LINK_SCOPE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalTenantId\":\"" + UUID.randomUUID() + "\",\"requestId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TENANT_LINK_NOT_CONFIGURED"));
        assertThat(store.links).isEmpty();
    }

    @Test
    @DisplayName("tenant creation is unaffected by the inventory configuration")
    void createStillGuardedByItsOwnScope() throws Exception {
        mockMvc.perform(get(TENANTS + "/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(PlatformScopes.TENANT_READ)))
                .andExpect(status().isNotFound());
    }
}
