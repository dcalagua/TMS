package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.entitlements.api.PlatformEntitlementsController;
import com.ebim.tms.iam.entitlements.application.ApplyEntitlementSnapshotService;
import com.ebim.tms.iam.entitlements.application.EntitlementApplyWriter;
import com.ebim.tms.iam.entitlements.application.EntitlementQueryService;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsConfig;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.provisioning.api.PlatformProvisioningController;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.infrastructure.InMemoryPlatformProvisioningRepository;
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
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The entitlements routes over HTTP, through the production MasterAdmin security chain: real ES256
 * verification, real scopes, real method security, real services. Replaced: the store (in memory) and
 * the transaction manager.
 */
@WebMvcTest(controllers = {PlatformEntitlementsController.class, PlatformProvisioningController.class})
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
    ApplyEntitlementSnapshotService.class,
    EntitlementApplyWriter.class,
    EntitlementQueryService.class,
    JtiGuard.class,
    PlatformEntitlementsApiTest.Fakes.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class PlatformEntitlementsApiTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4ccc-8000-000000001221");
    private static final String ENTITLEMENTS = "/internal/platform-provisioning/tenants/" + TENANT + "/entitlements";
    private static final String MANIFEST = "/internal/platform-provisioning/entitlements/manifest";
    private static final String WRITE = "tms:entitlements:write";
    private static final String READ = "tms:entitlements:read";

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        InMemoryEntitlementStore entitlementStore() {
            InMemoryEntitlementStore store = new InMemoryEntitlementStore(EnforcementMode.SHADOW);
            store.provision(TENANT);
            return store;
        }

        @Bean
        InMemoryPlatformProvisioningRepository platformProvisioningRepository() {
            return new InMemoryPlatformProvisioningRepository();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return Mockito.mock(PlatformTransactionManager.class);
        }
    }

    @DynamicPropertySource
    static void enable(DynamicPropertyRegistry registry) {
        registry.add("tms.platform-provisioning.enabled", () -> "true");
        registry.add("tms.platform-provisioning.public-key", PlatformM2mTestTokens::publicKeyPem);
        registry.add("tms.platform-entitlements.environment", () -> "DEV");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEntitlementStore store;

    private static MockHttpServletRequestBuilder putSnapshot(String token, String body) {
        MockHttpServletRequestBuilder request = put(ENTITLEMENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MasterAdmin-Contract", "entitlements.v1")
                .header("X-Correlation-Id", "corr-" + UUID.randomUUID())
                .content(body);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String nextSnapshot(boolean appActive) {
        long version = store.applied(TENANT).map(row -> row.version() + 1).orElse(1L);
        return TmsSnapshots.snapshot(TENANT, version, appActive).toString();
    }

    @Test
    @DisplayName("PUT with tms:entitlements:write applies; GET with tms:entitlements:read reads the same checksum")
    void putThenGet() throws Exception {
        String body = nextSnapshot(true);
        String checksum = JsonPath.read(body, "$.checksum");

        mockMvc.perform(putSnapshot(PlatformM2mTestTokens.valid(WRITE), body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedChecksum").value(checksum))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(bearer(get(ENTITLEMENTS), PlatformM2mTestTokens.valid(READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedChecksum").value(checksum))
                .andExpect(jsonPath("$.productCode").value("tms"))
                .andExpect(jsonPath("$.enforcementMode").value("SHADOW"));
    }

    @Test
    @DisplayName("a provisioning credential cannot write entitlements: 403 INSUFFICIENT_SCOPE, nothing stored")
    void provisioningScopeIsNotEnough() throws Exception {
        long before = store.audit.size();
        mockMvc.perform(putSnapshot(PlatformM2mTestTokens.valid(PlatformScopes.TENANT_CREATE + " "
                        + PlatformScopes.TENANT_READ), nextSnapshot(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        assertThat(store.audit).hasSize((int) before);
    }

    @Test
    @DisplayName("the read scope cannot write, and the write scope cannot read")
    void scopesAreSeparate() throws Exception {
        mockMvc.perform(putSnapshot(PlatformM2mTestTokens.valid(READ), nextSnapshot(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        mockMvc.perform(bearer(get(ENTITLEMENTS), PlatformM2mTestTokens.valid(WRITE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        mockMvc.perform(bearer(get(MANIFEST), PlatformM2mTestTokens.valid(WRITE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an entitlements credential cannot create a tenant")
    void entitlementsScopeCannotProvision() throws Exception {
        mockMvc.perform(bearer(post("/internal/platform-provisioning/tenants"), PlatformM2mTestTokens.valid(WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "ma-prov-v1-" + "c".repeat(64))
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_SCOPE"));
    }

    @Test
    @DisplayName("no token: 401 UNAUTHENTICATED; a forged one: 401 too")
    void unauthenticated() throws Exception {
        mockMvc.perform(putSnapshot(null, nextSnapshot(true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        mockMvc.perform(putSnapshot(PlatformM2mTestTokens.forged(WRITE), nextSnapshot(true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("the same token twice: the second request is 401 JTI_REPLAYED")
    void jtiReplay() throws Exception {
        String token = PlatformM2mTestTokens.valid(READ);
        mockMvc.perform(bearer(get(ENTITLEMENTS), token)).andExpect(status().isOk());
        mockMvc.perform(bearer(get(ENTITLEMENTS), token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("JTI_REPLAYED"));
    }

    @Test
    @DisplayName("a body that is not JSON by Content-Type is 415, and still spends the jti")
    void unsupportedMediaType() throws Exception {
        String token = PlatformM2mTestTokens.valid(WRITE);
        mockMvc.perform(put(ENTITLEMENTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.TEXT_PLAIN).content(nextSnapshot(true)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(putSnapshot(token, nextSnapshot(true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("JTI_REPLAYED"));
    }

    @Test
    @DisplayName("an unprovisioned tenant is 404 TENANT_NOT_PROVISIONED, and errors never echo the snapshot")
    void unprovisioned() throws Exception {
        UUID stranger = UUID.fromString("00000000-0000-4ccc-8000-000000001229");
        mockMvc.perform(put("/internal/platform-provisioning/tenants/" + stranger + "/entitlements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TmsSnapshots.snapshot(stranger, 1, true).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_PROVISIONED"))
                .andExpect(jsonPath("$.checksum").doesNotExist())
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    @DisplayName("GET /entitlements/manifest returns docs/platform-provisioning/ENTITLEMENTS_MANIFEST.json byte for byte")
    void manifest() throws Exception {
        byte[] served = mockMvc.perform(bearer(get(MANIFEST), PlatformM2mTestTokens.valid(READ)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(Files.readAllBytes(
                EntitlementsManifestTest.repositoryRoot().resolve(Path.of("docs", "platform-provisioning",
                        "ENTITLEMENTS_MANIFEST.json"))));
    }
}
