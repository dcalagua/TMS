package com.ebim.tms.iam.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.entitlements.InMemoryEntitlementStore;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsConfig;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.lifecycle.api.PlatformLifecycleController;
import com.ebim.tms.iam.lifecycle.application.LifecycleApplyWriter;
import com.ebim.tms.iam.lifecycle.application.PlatformLifecycleService;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The {@code lifecycle.v1} routes over HTTP, through the production MasterAdmin security chain: real ES256
 * verification, real scopes, real method security, real services and version rules. Replaced: the stores
 * (in memory) and the transaction manager.
 */
@WebMvcTest(controllers = {PlatformLifecycleController.class, PlatformProvisioningController.class})
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
    PlatformLifecycleService.class,
    LifecycleApplyWriter.class,
    PlatformLifecycleApiTest.Fakes.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class PlatformLifecycleApiTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4ccc-8000-000000005801");
    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-4ccc-8000-0000000058a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4ccc-8000-000000005809");
    private static final String LIFECYCLE = "/internal/platform-provisioning/tenants/" + TENANT + "/lifecycle";
    private static final String WRITE = PlatformScopes.LIFECYCLE_WRITE;
    private static final String READ = PlatformScopes.LIFECYCLE_READ;

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        InMemoryLifecycleStore lifecycleStore() {
            InMemoryLifecycleStore store = new InMemoryLifecycleStore();
            store.provision(TENANT, ORGANIZATION);
            return store;
        }

        /** Backs the shared single-use jti guard. */
        @Bean
        InMemoryEntitlementStore entitlementStore() {
            return new InMemoryEntitlementStore(EnforcementMode.SHADOW);
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
    private InMemoryLifecycleStore store;

    @BeforeEach
    void reset() {
        store.clear();
    }

    private static String body(UUID tenant, long version, String state, String reason) {
        return body(tenant, "tms", "DEV", version, state, reason, "");
    }

    private static String body(UUID tenant, String product, String environment, long version, String state,
            String reason, String extra) {
        return """
                {"contract":"lifecycle.v1","controlPlaneTenantId":"%s","productCode":"%s","environment":"%s",
                 "version":%d,"state":"%s","reason":%s,"effectiveAt":"2026-10-13T11:00:00Z",
                 "idempotencyKey":"lifecycle:%s:tms:%d","correlationId":"corr-%d"%s}
                """.formatted(tenant, product, environment, version, state,
                reason == null ? "null" : "\"" + reason + "\"", tenant, version, version, extra);
    }

    private ResultActions putLifecycle(String token, String body) throws Exception {
        return putLifecycle(LIFECYCLE, token, body);
    }

    private ResultActions putLifecycle(String path, String token, String body) throws Exception {
        MockHttpServletRequestBuilder request = put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MasterAdmin-Contract", "lifecycle.v1")
                .header("Idempotency-Key", "lifecycle-key")
                .header("X-Correlation-Id", "corr-" + UUID.randomUUID())
                .content(body);
        return mockMvc.perform(token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions getLifecycle(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    @DisplayName("GET before any PUT: appliedVersion null, state ACTIVE")
    void neverSetIsActive() throws Exception {
        getLifecycle(LIFECYCLE, PlatformM2mTestTokens.valid(READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlPlaneTenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.productCode").value("tms"))
                .andExpect(jsonPath("$.appliedVersion").isEmpty())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.appliedAt").isEmpty());
    }

    @Test
    @DisplayName("SUSPENDED v1 applies; GET returns what is stored; ACTIVE v2 reactivates")
    void suspendThenReactivate() throws Exception {
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 1, "SUSPENDED", "NON_PAYMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlPlaneTenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.productCode").value("tms"))
                .andExpect(jsonPath("$.appliedVersion").value(1))
                .andExpect(jsonPath("$.state").value("SUSPENDED"))
                .andExpect(jsonPath("$.reason").value("NON_PAYMENT"))
                .andExpect(jsonPath("$.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.replayed").value(false));

        getLifecycle(LIFECYCLE, PlatformM2mTestTokens.valid(READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedVersion").value(1))
                .andExpect(jsonPath("$.state").value("SUSPENDED"))
                .andExpect(jsonPath("$.reason").value("NON_PAYMENT"));

        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 2, "ACTIVE", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedVersion").value(2))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.replayed").value(false));

        assertThat(store.audit).extracting(InMemoryLifecycleStore.AuditRow::outcome)
                .containsExactly("APPLIED", "APPLIED");
        assertThat(store.locks).isEqualTo(2);
    }

    @Test
    @DisplayName("same version and same state: 200 replayed=true, nothing changes")
    void replay() throws Exception {
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 3, "SUSPENDED", "MANUAL"))
                .andExpect(status().isOk());
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 3, "SUSPENDED", "MANUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedVersion").value(3))
                .andExpect(jsonPath("$.state").value("SUSPENDED"))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(store.audit).extracting(InMemoryLifecycleStore.AuditRow::outcome)
                .containsExactly("APPLIED", "REPLAYED");
    }

    @Test
    @DisplayName("same version with another state: 409 VERSION_CONFLICT with the applied version")
    void versionConflict() throws Exception {
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 4, "SUSPENDED", "NON_PAYMENT"))
                .andExpect(status().isOk());
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 4, "ACTIVE", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.appliedVersion").value(4));
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 4, "SUSPENDED", "MANUAL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"));

        getLifecycle(LIFECYCLE, PlatformM2mTestTokens.valid(READ))
                .andExpect(jsonPath("$.state").value("SUSPENDED"))
                .andExpect(jsonPath("$.reason").value("NON_PAYMENT"));
        assertThat(store.audit).extracting(InMemoryLifecycleStore.AuditRow::outcome)
                .containsExactly("APPLIED", "VERSION_CONFLICT", "VERSION_CONFLICT");
    }

    @Test
    @DisplayName("an older version: 409 STALE_LIFECYCLE with the applied version, state unchanged")
    void stale() throws Exception {
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 7, "ACTIVE", null)).andExpect(status().isOk());
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, 6, "SUSPENDED", "NON_PAYMENT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_LIFECYCLE"))
                .andExpect(jsonPath("$.appliedVersion").value(7));

        getLifecycle(LIFECYCLE, PlatformM2mTestTokens.valid(READ))
                .andExpect(jsonPath("$.appliedVersion").value(7))
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    @DisplayName("a tenant not provisioned here: 404 TENANT_NOT_PROVISIONED on PUT and GET")
    void unprovisioned() throws Exception {
        String path = "/internal/platform-provisioning/tenants/" + STRANGER + "/lifecycle";
        putLifecycle(path, PlatformM2mTestTokens.valid(WRITE), body(STRANGER, 1, "SUSPENDED", "MANUAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_PROVISIONED"));
        getLifecycle(path, PlatformM2mTestTokens.valid(READ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_PROVISIONED"));
        getLifecycle("/internal/platform-provisioning/tenants/not-a-uuid/lifecycle", PlatformM2mTestTokens.valid(READ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_PROVISIONED"));
        assertThat(store.audit).isEmpty();
    }

    @Test
    @DisplayName("product and environment are the receiver's: 400 PRODUCT_MISMATCH / WRONG_ENVIRONMENT")
    void productAndEnvironment() throws Exception {
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, "ewm", "DEV", 1, "SUSPENDED", "MANUAL", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PRODUCT_MISMATCH"));
        putLifecycle(PlatformM2mTestTokens.valid(WRITE), body(TENANT, "tms", "QAS", 1, "SUSPENDED", "MANUAL", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WRONG_ENVIRONMENT"));
        assertThat(store.applied(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("an invalid body is 400 INVALID_LIFECYCLE and never echoes the request")
    void invalidBodies() throws Exception {
        String[] invalid = {
            body(TENANT, "tms", "DEV", 1, "SUSPENDED", "MANUAL", ",\"amount\":10"),
            body(TENANT, 1, "SUSPENDED", null),
            body(TENANT, 1, "ACTIVE", "MANUAL"),
            body(TENANT, 1, "PAUSED", null),
            body(TENANT, 1, "SUSPENDED", "LATE"),
            body(TENANT, 0, "ACTIVE", null),
            body(STRANGER, 1, "ACTIVE", null),
            body(TENANT, 1, "ACTIVE", null).replace("lifecycle.v1", "lifecycle.v2"),
            body(TENANT, 1, "ACTIVE", null).replace("\"version\":1", "\"version\":\"1\""),
            body(TENANT, 1, "ACTIVE", null).replace("2026-10-13T11:00:00Z", "yesterday"),
            body(TENANT, 1, "ACTIVE", null).replace("\"productCode\":\"tms\",", ""),
            "{\"contract\":\"lifecycle.v1\",\"contract\":\"lifecycle.v1\"}",
            "not json",
            "[]",
        };
        for (String document : invalid) {
            putLifecycle(PlatformM2mTestTokens.valid(WRITE), document)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_LIFECYCLE"))
                    .andExpect(jsonPath("$.controlPlaneTenantId").doesNotExist())
                    .andExpect(jsonPath("$.state").doesNotExist());
        }
        assertThat(store.applied(TENANT)).isEmpty();
        assertThat(store.audit).isEmpty();
    }

    @Test
    @DisplayName("a contract header other than lifecycle.v1, or a non-JSON body, is 400 INVALID_LIFECYCLE")
    void headerAndMediaType() throws Exception {
        mockMvc.perform(put(LIFECYCLE).contentType(MediaType.APPLICATION_JSON)
                        .header("X-MasterAdmin-Contract", "entitlements.v1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(WRITE))
                        .content(body(TENANT, 1, "ACTIVE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LIFECYCLE"));
        String token = PlatformM2mTestTokens.valid(WRITE);
        mockMvc.perform(put(LIFECYCLE).contentType(MediaType.TEXT_PLAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .content(body(TENANT, 1, "ACTIVE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LIFECYCLE"));
        // ... and that refusal spent the jti.
        putLifecycle(token, body(TENANT, 1, "ACTIVE", null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("JTI_REPLAYED"));
    }

    @Test
    @DisplayName("scopes are separate: entitlements and provisioning scopes cannot suspend; read cannot write")
    void scopes() throws Exception {
        putLifecycle(PlatformM2mTestTokens.valid(PlatformScopes.ENTITLEMENTS_WRITE + " " + PlatformScopes.TENANT_CREATE),
                        body(TENANT, 1, "SUSPENDED", "MANUAL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        putLifecycle(PlatformM2mTestTokens.valid(READ), body(TENANT, 1, "SUSPENDED", "MANUAL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        getLifecycle(LIFECYCLE, PlatformM2mTestTokens.valid(WRITE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        getLifecycle(LIFECYCLE, PlatformM2mTestTokens.valid(PlatformScopes.ENTITLEMENTS_READ))
                .andExpect(status().isForbidden());
        assertThat(store.applied(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("a lifecycle credential cannot create a tenant")
    void lifecycleScopeCannotProvision() throws Exception {
        mockMvc.perform(post("/internal/platform-provisioning/tenants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "ma-prov-v1-" + "d".repeat(64))
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_SCOPE"));
    }

    @Test
    @DisplayName("no token: 401 UNAUTHENTICATED; a forged one: 401 too")
    void unauthenticated() throws Exception {
        putLifecycle(null, body(TENANT, 1, "SUSPENDED", "MANUAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        putLifecycle(PlatformM2mTestTokens.forged(WRITE), body(TENANT, 1, "SUSPENDED", "MANUAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        assertThat(store.applied(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("the same token twice: the second request is 401 JTI_REPLAYED")
    void jtiReplay() throws Exception {
        String token = PlatformM2mTestTokens.valid(READ);
        getLifecycle(LIFECYCLE, token).andExpect(status().isOk());
        getLifecycle(LIFECYCLE, token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("JTI_REPLAYED"));
    }

    @Test
    @DisplayName("other methods on the lifecycle route are denied by the chain")
    void otherMethodsDenied() throws Exception {
        mockMvc.perform(post(LIFECYCLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(WRITE + " " + READ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TENANT, 1, "ACTIVE", null)))
                .andExpect(status().isForbidden());
    }
}
