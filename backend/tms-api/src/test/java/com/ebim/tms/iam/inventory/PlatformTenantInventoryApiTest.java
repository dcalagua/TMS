package com.ebim.tms.iam.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.entitlements.InMemoryEntitlementStore;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsConfig;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.inventory.api.PlatformTenantInventoryController;
import com.ebim.tms.iam.inventory.application.PlatformTenantInventoryService;
import com.ebim.tms.iam.provisioning.api.PlatformProvisioningController;
import com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Operation;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Result;
import com.ebim.tms.iam.provisioning.infrastructure.InMemoryPlatformProvisioningRepository;
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
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
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
 * {@code tenant-inventory.v1} over HTTP, through the production MasterAdmin security chain: real ES256
 * verification, configured scopes, method security, the real services - and the real create and status
 * routes, so that "the inventory lists what create returned" and "status resolves a linked tenant" are
 * proven end to end. Replaced: the stores (in memory, one shared state) and the transaction manager.
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
class PlatformTenantInventoryApiTest {

    static final String LIST_SCOPE = "tms:tenants:list";
    static final String LINK_SCOPE = "tms:tenants:link";
    private static final String TENANTS = "/internal/platform-provisioning/tenants";

    private static final UUID LEGACY = UUID.fromString("00000000-0000-4ccc-8000-0000000076a1");
    private static final UUID ARCHIVED = UUID.fromString("00000000-0000-4ccc-8000-0000000076a2");
    private static final UUID PAUSED = UUID.fromString("00000000-0000-4ccc-8000-0000000076a3");
    private static final UUID CPT_X = UUID.fromString("00000000-0000-4ccc-8000-000000007601");
    private static final UUID CPT_Y = UUID.fromString("00000000-0000-4ccc-8000-000000007602");

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        InMemoryPlatformProvisioningRepository platformProvisioningRepository() {
            return new InMemoryPlatformProvisioningRepository();
        }

        @Bean
        InMemoryTenantInventoryStore tenantInventoryStore(InMemoryPlatformProvisioningRepository provisioning) {
            return new InMemoryTenantInventoryStore(provisioning);
        }

        /** Backs the shared single-use jti guard. */
        @Bean
        InMemoryEntitlementStore entitlementStore() {
            return new InMemoryEntitlementStore(EnforcementMode.SHADOW);
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
        registry.add("tms.platform-inventory.read-scope", () -> LIST_SCOPE);
        registry.add("tms.platform-inventory.link-scope", () -> LINK_SCOPE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryPlatformProvisioningRepository repository;

    @Autowired
    private InMemoryTenantInventoryStore store;

    private UUID legacyOldestCompany;

    @BeforeEach
    void seed() {
        repository.reset();
        store.clear();
        repository.addLegacyOrganization(LEGACY, "QAS-LEGACY", "Legacy (demo)", "LEGACY-LIMA", "LEGACY-AQP");
        repository.addLegacyOrganization(ARCHIVED, "QAS-ARCHIVED", "Archived", "ARCH-1");
        repository.addLegacyOrganization(PAUSED, "QAS-PAUSED", "Paused", "PAUSED-1");
        repository.inactiveOrganizations.add(ARCHIVED);
        store.suspendedOrganizations.add(PAUSED);
        legacyOldestCompany = repository.oldestCompany(LEGACY);
    }

    private ResultActions list(String token, String query) throws Exception {
        return mockMvc.perform(get(TENANTS + (query == null ? "" : "?" + query))
                .header("X-MasterAdmin-Contract", "tenant-inventory.v1")
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions link(String token, UUID tenant, String body) throws Exception {
        return link(token, TENANTS + "/" + tenant + "/link", body);
    }

    private ResultActions link(String token, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MasterAdmin-Contract", "tenant-inventory.v1")
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .content(body);
        return mockMvc.perform(token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String linkBody(Object externalTenantId) {
        return "{\"externalTenantId\":\"" + externalTenantId + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}";
    }

    private String create(String tenantId, String code) throws Exception {
        String response = mockMvc.perform(post(TENANTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(PlatformScopes.TENANT_CREATE))
                        .header("Idempotency-Key", "ma-prov-v1-" + code + "-0000000")
                        .header("X-MasterAdmin-Contract", "v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GenericProvisioningFixtures.json(tenantId, code, code + "@inventory.ebim.test", code)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.externalTenantId");
    }

    private List<String> auditOf(Operation operation) {
        return repository.audit.stream().filter(entry -> entry.operation() == operation)
                .map(ProvisioningAuditEntry::result).map(Result::name).toList();
    }

    // ------------------------------------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("lists every organization with identity and status; externalTenantId is what create returned")
    void listsEverythingWithCreateParity() throws Exception {
        String created = create(GenericProvisioningFixtures.TENANT_A, "alpha-tms");

        list(PlatformM2mTestTokens.valid(LIST_SCOPE), null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.contract").value("tenant-inventory.v1"))
                .andExpect(jsonPath("$.productCode").value("tms"))
                .andExpect(jsonPath("$.environment").value("DEV"))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.items.length()").value(4))
                // Created by MasterAdmin: the same id create answered with, and its controlPlaneTenantId.
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].controlPlaneTenantId", created)
                        .value(GenericProvisioningFixtures.TENANT_A))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].externalOrganizationId", created)
                        .value(created))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].code", created).value("ALPHA-TMS"))
                // Existing only in TMS: no controlPlaneTenantId, its oldest company, its own status.
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].controlPlaneTenantId", LEGACY).value(
                        org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].externalCompanyId", LEGACY)
                        .value(legacyOldestCompany.toString()))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].name", LEGACY).value("Legacy (demo)"))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].status", LEGACY).value("ACTIVE"))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].status", ARCHIVED).value("INACTIVE"))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].status", PAUSED).value("SUSPENDED"))
                // Identity and state only.
                .andExpect(jsonPath("$.items[0].adminEmail").doesNotExist())
                .andExpect(jsonPath("$.items[0].taxId").doesNotExist());

        assertThat(auditOf(Operation.LIST_TENANTS)).containsExactly("FOUND");
    }

    @Test
    @DisplayName("keyset pagination: limit=1 walks every tenant once, in id order, until nextCursor is null")
    void paginates() throws Exception {
        create(GenericProvisioningFixtures.TENANT_A, "alpha-tms");
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String response = list(PlatformM2mTestTokens.valid(LIST_SCOPE), "limit=1" + (cursor == null ? "" : "&cursor=" + cursor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andReturn().getResponse().getContentAsString();
            seen.add(JsonPath.read(response, "$.items[0].externalTenantId"));
            cursor = JsonPath.read(response, "$.nextCursor");
            if (cursor == null) {
                break;
            }
            assertThat(cursor).hasSizeLessThanOrEqualTo(500).doesNotContain(seen.getLast());
        }
        assertThat(seen).hasSize(4).doesNotHaveDuplicates().isSorted();
        assertThat(seen).contains(LEGACY.toString(), ARCHIVED.toString(), PAUSED.toString());
    }

    @Test
    @DisplayName("an invalid limit, cursor or contract header is 400 INVALID_INVENTORY_QUERY")
    void invalidQueries() throws Exception {
        for (String query : new String[] {"limit=0", "limit=501", "limit=abc", "limit=-1", "limit=", "limit=1.5",
                "cursor=not-a-cursor", "cursor=", "cursor=" + UUID.randomUUID()}) {
            list(PlatformM2mTestTokens.valid(LIST_SCOPE), query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_INVENTORY_QUERY"))
                    .andExpect(jsonPath("$.items").doesNotExist());
        }
        mockMvc.perform(get(TENANTS).header("X-MasterAdmin-Contract", "lifecycle.v1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(LIST_SCOPE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INVENTORY_QUERY"));
        list(PlatformM2mTestTokens.valid(LIST_SCOPE), "limit=500").andExpect(status().isOk());
    }

    @Test
    @DisplayName("listing needs the list scope: create, read, lifecycle and link scopes are 403")
    void listScope() throws Exception {
        for (String scope : new String[] {PlatformScopes.TENANT_CREATE, PlatformScopes.TENANT_READ,
                PlatformScopes.LIFECYCLE_READ, PlatformScopes.ENTITLEMENTS_READ, LINK_SCOPE}) {
            list(PlatformM2mTestTokens.valid(scope), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        }
        mockMvc.perform(get(TENANTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        list(PlatformM2mTestTokens.forged(LIST_SCOPE), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("the same token twice: the second list is 401 JTI_REPLAYED")
    void listJtiReplay() throws Exception {
        String token = PlatformM2mTestTokens.valid(LIST_SCOPE);
        list(token, null).andExpect(status().isOk());
        list(token, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("JTI_REPLAYED"));
    }

    @Test
    @DisplayName("a failing read is 500 INVENTORY_FAILED, a stable code and no trace")
    void readFailure() throws Exception {
        store.failure = new IllegalStateException("connection refused to db.internal:5432");
        list(PlatformM2mTestTokens.valid(LIST_SCOPE), null)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INVENTORY_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("5432"))));
        assertThat(auditOf(Operation.LIST_TENANTS)).containsExactly("ERROR");
    }

    // ------------------------------------------------------------------------------------------------
    // Link
    // ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("link: LINKED, then ALREADY_LINKED; status, list and audit then see the tenant as provisioned")
    void linkThenStatus() throws Exception {
        String token = PlatformM2mTestTokens.valid(LINK_SCOPE);
        link(token, CPT_X, linkBody(LEGACY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract").value("tenant-inventory.v1"))
                .andExpect(jsonPath("$.productCode").value("tms"))
                .andExpect(jsonPath("$.environment").value("DEV"))
                .andExpect(jsonPath("$.controlPlaneTenantId").value(CPT_X.toString()))
                .andExpect(jsonPath("$.externalTenantId").value(LEGACY.toString()))
                .andExpect(jsonPath("$.status").value("LINKED"));
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_X, linkBody(LEGACY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_LINKED"));

        // The status route resolves it exactly like a created tenant.
        mockMvc.perform(get(TENANTS + "/" + CPT_X)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(PlatformScopes.TENANT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.controlPlaneTenantId").value(CPT_X.toString()))
                .andExpect(jsonPath("$.externalTenantId").value(LEGACY.toString()))
                .andExpect(jsonPath("$.externalOrganizationId").value(LEGACY.toString()))
                .andExpect(jsonPath("$.externalCompanyId").value(legacyOldestCompany.toString()))
                .andExpect(jsonPath("$.resources.linked").value(true))
                .andExpect(jsonPath("$.resources.adminStatus").doesNotExist());

        // The list now carries the controlPlaneTenantId, and the company it reported before the link.
        list(PlatformM2mTestTokens.valid(LIST_SCOPE), null)
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].controlPlaneTenantId", LEGACY)
                        .value(CPT_X.toString()))
                .andExpect(jsonPath("$.items[?(@.externalTenantId == '%s')].externalCompanyId", LEGACY)
                        .value(legacyOldestCompany.toString()));

        // Nothing of the tenant itself was created or changed.
        assertThat(repository.organizations).hasSize(3);
        assertThat(repository.companies).hasSize(4);
        assertThat(repository.profiles).isEmpty();
        assertThat(repository.memberships).isEmpty();
        assertThat(store.locks).containsExactly("tenant:" + CPT_X, "organization:" + LEGACY,
                "tenant:" + CPT_X, "organization:" + LEGACY);
        assertThat(store.links).singleElement().satisfies(written -> {
            assertThat(written.companyId()).isEqualTo(legacyOldestCompany);
            assertThat(written.tenantCode()).isEqualTo("QAS-LEGACY");
            assertThat(written.environment()).isEqualTo("DEV");
            assertThat(written.requestHash()).matches("[0-9a-f]{64}");
        });
        assertThat(auditOf(Operation.LINK_TENANT)).containsExactly("LINKED", "ALREADY_LINKED");
        assertThat(repository.audit.stream().filter(e -> e.operation() == Operation.LINK_TENANT))
                .allSatisfy(entry -> {
                    assertThat(entry.controlPlaneTenantId()).isEqualTo(CPT_X);
                    assertThat(entry.provisioningId()).isNotNull();
                    assertThat(entry.idempotencyKey()).isNotNull(); // MasterAdmin's requestId
                });
    }

    @Test
    @DisplayName("link conflicts: either side mapped elsewhere is 409 LINK_CONFLICT; a created tenant confirms")
    void linkConflicts() throws Exception {
        String created = create(GenericProvisioningFixtures.TENANT_A, "alpha-tms");
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_X, linkBody(LEGACY)).andExpect(status().isOk());

        // The organization is already linked to X.
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_Y, linkBody(LEGACY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LINK_CONFLICT"));
        // X is already linked to another organization.
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_X, linkBody(PAUSED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LINK_CONFLICT"));
        // A tenant MasterAdmin created cannot be adopted by another MasterAdmin tenant...
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_Y, linkBody(created))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LINK_CONFLICT"));
        // ... and its own pair is confirmed.
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), UUID.fromString(GenericProvisioningFixtures.TENANT_A),
                        linkBody(created))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_LINKED"));

        assertThat(store.links).hasSize(1);
        assertThat(auditOf(Operation.LINK_TENANT))
                .containsExactly("LINKED", "CONFLICT", "CONFLICT", "CONFLICT", "ALREADY_LINKED");
    }

    @Test
    @DisplayName("an externalTenantId TMS does not have is 404 EXTERNAL_TENANT_NOT_FOUND, nothing written")
    void linkUnknownTenant() throws Exception {
        for (String external : new String[] {UUID.randomUUID().toString(), "46", "not-a-tms-id"}) {
            link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_Y, linkBody(external))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("EXTERNAL_TENANT_NOT_FOUND"));
        }
        assertThat(store.links).isEmpty();
        mockMvc.perform(get(TENANTS + "/" + CPT_Y)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(PlatformScopes.TENANT_READ)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an invalid link request is 400 INVALID_LINK_REQUEST and never echoes the request")
    void invalidLinks() throws Exception {
        String[] invalid = {
            "{\"externalTenantId\":\"" + LEGACY + "\"}",
            "{\"externalTenantId\":\"" + LEGACY + "\",\"requestId\":\"nope\"}",
            "{\"requestId\":\"" + UUID.randomUUID() + "\"}",
            "{\"externalTenantId\":\"\",\"requestId\":\"" + UUID.randomUUID() + "\"}",
            "{\"externalTenantId\":42,\"requestId\":\"" + UUID.randomUUID() + "\"}",
            "{\"externalTenantId\":\"" + "x".repeat(201) + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}",
            "{\"externalTenantId\":\"" + LEGACY + "\",\"requestId\":\"" + UUID.randomUUID()
                    + "\",\"controlPlaneTenantId\":\"" + CPT_Y + "\"}",
            "{\"externalTenantId\":\"" + LEGACY + "\",\"requestId\":\"" + UUID.randomUUID() + "\",\"productCode\":\"ewm\"}",
            "{\"externalTenantId\":\"a\",\"externalTenantId\":\"b\",\"requestId\":\"" + UUID.randomUUID() + "\"}",
            "not json",
            "[]",
            "",
        };
        for (String body : invalid) {
            link(PlatformM2mTestTokens.valid(LINK_SCOPE), CPT_X, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_LINK_REQUEST"))
                    .andExpect(jsonPath("$.externalTenantId").doesNotExist());
        }
        link(PlatformM2mTestTokens.valid(LINK_SCOPE), TENANTS + "/not-a-uuid/link", linkBody(LEGACY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LINK_REQUEST"));
        mockMvc.perform(put(TENANTS + "/" + CPT_X + "/link").contentType(MediaType.TEXT_PLAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(LINK_SCOPE))
                        .content(linkBody(LEGACY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LINK_REQUEST"));
        mockMvc.perform(put(TENANTS + "/" + CPT_X + "/link").contentType(MediaType.APPLICATION_JSON)
                        .header("X-MasterAdmin-Contract", "v1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(LINK_SCOPE))
                        .content(linkBody(LEGACY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LINK_REQUEST"));
        assertThat(store.links).isEmpty();
    }

    @Test
    @DisplayName("scopes are separate: create, list and lifecycle scopes cannot link; the link scope cannot create")
    void linkScope() throws Exception {
        for (String scope : new String[] {PlatformScopes.TENANT_CREATE, LIST_SCOPE, PlatformScopes.LIFECYCLE_WRITE,
                PlatformScopes.ENTITLEMENTS_WRITE}) {
            link(PlatformM2mTestTokens.valid(scope), CPT_X, linkBody(LEGACY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_SCOPE"));
        }
        link(null, CPT_X, linkBody(LEGACY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        mockMvc.perform(post(TENANTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(LINK_SCOPE))
                        .header("Idempotency-Key", "ma-prov-v1-" + "e".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_SCOPE"));
        // Other methods on the link route are denied by the chain.
        mockMvc.perform(post(TENANTS + "/" + CPT_X + "/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(LINK_SCOPE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody(LEGACY)))
                .andExpect(status().isForbidden());
        assertThat(store.links).isEmpty();
    }

    @Test
    @DisplayName("the same token twice: the second link is 401 JTI_REPLAYED, even after a refusal")
    void linkJtiReplay() throws Exception {
        String token = PlatformM2mTestTokens.valid(LINK_SCOPE);
        link(token, CPT_X, "not json").andExpect(status().isBadRequest());
        link(token, CPT_X, linkBody(LEGACY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("JTI_REPLAYED"));
        assertThat(store.links).isEmpty();
    }
}
