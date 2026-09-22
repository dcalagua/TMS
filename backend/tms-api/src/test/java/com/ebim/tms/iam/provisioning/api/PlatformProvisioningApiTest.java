package com.ebim.tms.iam.provisioning.api;

import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.TENANT_A;
import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.TENANT_B;
import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Result;
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
import com.ebim.tms.shared.security.TestJwts;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.security.TmsSecurityProperties;
import com.ebim.tms.shared.web.WebConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The MasterAdmin GENERIC contract over HTTP, through the production security chains.
 *
 * <p>Real: both security chains (MasterAdmin's and the user one), the ES256 verification, method
 * security, the controller, the use cases and the error bodies. Replaced: the repository, by an
 * in-memory one that keeps state across calls so idempotency is exercised end to end, and the
 * transaction manager (its behaviour is proven in {@code PlatformTenantProvisionerTransactionTest}).
 */
@WebMvcTest(controllers = PlatformProvisioningController.class)
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
    PlatformProvisioningApiTest.Fakes.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class PlatformProvisioningApiTest {

    private static final String TENANTS = "/internal/platform-provisioning/tenants";
    private static final String HEALTH = "/internal/platform-provisioning/health";
    private static final String CREATE = PlatformScopes.TENANT_CREATE;
    private static final String READ = PlatformScopes.TENANT_READ;
    private static final String KEY_A = "ma-prov-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String KEY_B = "ma-prov-v1-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

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
    static void enableProvisioning(DynamicPropertyRegistry registry) {
        registry.add("tms.platform-provisioning.enabled", () -> "true");
        registry.add("tms.platform-provisioning.public-key", PlatformM2mTestTokens::publicKeyPem);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryPlatformProvisioningRepository repository;

    @BeforeEach
    void clean() {
        repository.reset();
    }

    private static MockHttpServletRequestBuilder create(String token, String key, String body) {
        MockHttpServletRequestBuilder request = post(TENANTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MasterAdmin-Contract", "v1")
                .header("X-Correlation-Id", "corr-" + UUID.randomUUID())
                .content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request;
    }

    private static String bodyA() {
        return json(TENANT_A, "alpha-tms", "admin@alpha.ebim.test", "Alpha Logistica");
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("health")
    class Health {

        @Test
        @DisplayName("200 {status: ok}, anonymous, fixed body")
        void ok() throws Exception {
            mockMvc.perform(get(HEALTH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.issuer").doesNotExist());
        }

        @Test
        @DisplayName("a stale or garbage token sent to the probe is ignored, not a 401")
        void tokenIgnored() throws Exception {
            mockMvc.perform(get(HEALTH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                    .andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("authentication and scopes")
    class Security {

        @Test
        @DisplayName("no token: 401 UNAUTHENTICATED")
        void missingToken() throws Exception {
            mockMvc.perform(create(null, KEY_A, bodyA()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            assertThat(repository.organizations).isEmpty();
        }

        @Test
        @DisplayName("HS256 (wrong algorithm): 401 INVALID_M2M_TOKEN")
        void wrongAlgorithm() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.hs256(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_M2M_TOKEN"));
        }

        @Test
        @DisplayName("wrong issuer: 401")
        void wrongIssuer() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.with(CREATE, c -> c.issuer("evil.ebim")), KEY_A, bodyA()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_M2M_TOKEN"));
        }

        @Test
        @DisplayName("wrong audience (a token for EWM): 401")
        void wrongAudience() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.with(CREATE, c -> c.audience("ewm.ebim")), KEY_A, bodyA()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_M2M_TOKEN"));
            assertThat(repository.organizations).isEmpty();
        }

        @Test
        @DisplayName("create without tms:tenant:create: 403 MISSING_SCOPE, nothing created")
        void missingCreateScope() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.valid(READ), KEY_A, bodyA()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MISSING_SCOPE"));
            assertThat(repository.organizations).isEmpty();
        }

        @Test
        @DisplayName("status without tms:tenant:read: 403 MISSING_SCOPE")
        void missingReadScope() throws Exception {
            mockMvc.perform(get(TENANTS + "/" + TENANT_A)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(CREATE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MISSING_SCOPE"));
        }

        @Test
        @DisplayName("a Supabase user token is not a MasterAdmin token")
        void userTokenRefused() throws Exception {
            mockMvc.perform(create(TestJwts.validFor(UUID.randomUUID()), KEY_A, bodyA()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_M2M_TOKEN"));
        }

        @Test
        @DisplayName("a MasterAdmin token opens nothing on the user API")
        void machineTokenRefusedOnUserApi() throws Exception {
            mockMvc.perform(get("/api/v1/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(CREATE + " " + READ)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unlisted method under the prefix is denied even with every scope")
        void unlistedMethodDenied() throws Exception {
            mockMvc.perform(post(TENANTS + "/" + TENANT_A)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(CREATE + " " + READ)))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("create, replay and conflicts")
    class Create {

        @Test
        @DisplayName("first call: 201 with the GENERIC response and the TMS ids")
        void firstCreate() throws Exception {
            String response = mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.replayed").value(false))
                    .andExpect(jsonPath("$.controlPlaneTenantId").value(TENANT_A))
                    .andExpect(jsonPath("$.resources.organizationCode").value("ALPHA-TMS"))
                    .andExpect(jsonPath("$.resources.companyCode").value("ALPHA-01"))
                    .andExpect(jsonPath("$.resources.companyTimeZone").value("America/Lima"))
                    .andExpect(jsonPath("$.resources.adminStatus").value("PREPROVISIONED"))
                    .andReturn().getResponse().getContentAsString();

            // Tenant mapping: externalTenantId is the TMS organization, the company is inside it.
            String organizationId = JsonPath.read(response, "$.externalTenantId");
            String companyId = JsonPath.read(response, "$.externalCompanyId");
            assertThat(organizationId).isEqualTo(JsonPath.read(response, "$.externalOrganizationId"));
            assertThat(repository.organizations).containsOnlyKeys(UUID.fromString(organizationId));
            assertThat(repository.companies.get(UUID.fromString(companyId)).organizationId())
                    .isEqualTo(UUID.fromString(organizationId));
            assertThat(repository.companySettings).containsEntry(UUID.fromString(companyId), "PE");

            // The administrator: an organization-wide ORGANIZATION_ADMIN membership, and no login.
            assertThat(repository.memberships.values()).singleElement().satisfies(m -> {
                assertThat(m.organizationId()).isEqualTo(UUID.fromString(organizationId));
                assertThat(m.companyId()).as("organization-wide").isNull();
                assertThat(m.roles()).containsExactly("ORGANIZATION_ADMIN");
            });
            assertThat(repository.profiles.values()).singleElement().satisfies(p -> {
                assertThat(p.email()).isEqualTo("admin@alpha.ebim.test");
                assertThat(p.authUserId()).as("Auth is never activated by provisioning").isNull();
            });
            assertThat(repository.audit).extracting(ProvisioningAuditEntry::result).containsExactly(Result.CREATED);
        }

        @Test
        @DisplayName("replay with the same key and body: 200 replayed, same ids, nothing duplicated")
        void replay() throws Exception {
            String first = mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

            // A retry carries a new token (new jti) and MasterAdmin's fields regenerate; the key does not.
            String replay = mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andReturn().getResponse().getContentAsString();

            assertThat((String) JsonPath.read(replay, "$.externalTenantId"))
                    .isEqualTo(JsonPath.read(first, "$.externalTenantId"));
            assertThat((String) JsonPath.read(replay, "$.externalCompanyId"))
                    .isEqualTo(JsonPath.read(first, "$.externalCompanyId"));
            assertThat(repository.organizations).hasSize(1);
            assertThat(repository.companies).hasSize(1);
            assertThat(repository.memberships).hasSize(1);
            assertThat(repository.audit).extracting(ProvisioningAuditEntry::result)
                    .containsExactly(Result.CREATED, Result.REPLAYED);
        }

        @Test
        @DisplayName("same key, different body: 409 IDEMPOTENCY_CONFLICT")
        void replayDifferentBody() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isCreated());

            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A,
                            json(TENANT_A, "alpha-tms", "admin@alpha.ebim.test", "Another Name")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
            assertThat(repository.companies).hasSize(1);
            assertThat(repository.audit).extracting(ProvisioningAuditEntry::result)
                    .containsExactly(Result.CREATED, Result.CONFLICT);
        }

        @Test
        @DisplayName("duplicate prevention: another key for the same MasterAdmin tenant creates nothing")
        void duplicateTenant() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isCreated());

            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_B, bodyA()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TENANT_ALREADY_PROVISIONED"));
            assertThat(repository.organizations).hasSize(1);
        }

        @Test
        @DisplayName("an organization code TMS already has (seeded, not provisioned) is never adopted")
        void existingOrganizationCode() throws Exception {
            repository.addOrganization("ALPHA-TMS");

            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
            assertThat(repository.companies).isEmpty();
        }

        @Test
        @DisplayName("missing Idempotency-Key: 400 IDEMPOTENCY_KEY_REQUIRED")
        void missingKey() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), null, bodyA()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        }

        @Test
        @DisplayName("invalid body: 400 INVALID_REQUEST naming GENERIC fields")
        void invalidBody() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A,
                            json("not-a-uuid", "alpha tms", "nope", "X")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                            "masterAdmin.tenantId", "tenantCode", "adminEmail")));
            assertThat(repository.organizations).isEmpty();
        }

        @Test
        @DisplayName("malformed JSON: 400 INVALID_REQUEST; wrong content type: 415")
        void malformed() throws Exception {
            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, "{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA())
                            .contentType(MediaType.TEXT_PLAIN))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("status and tenant isolation")
    class Status {

        @Test
        @DisplayName("GET an existing tenant: 200 with the same ids, no replayed flag")
        void existing() throws Exception {
            String created = mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

            mockMvc.perform(get(TENANTS + "/" + TENANT_A)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(READ)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.externalTenantId").value((String) JsonPath.read(created, "$.externalTenantId")))
                    .andExpect(jsonPath("$.replayed").doesNotExist());
            assertThat(repository.audit).extracting(ProvisioningAuditEntry::result)
                    .containsExactly(Result.CREATED, Result.FOUND);
        }

        @Test
        @DisplayName("GET a tenant never provisioned: 404 PROVISIONING_NOT_FOUND")
        void missing() throws Exception {
            mockMvc.perform(get(TENANTS + "/" + TENANT_B)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(READ)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROVISIONING_NOT_FOUND"));
        }

        @Test
        @DisplayName("GET with a non-UUID id: 400")
        void notAUuid() throws Exception {
            mockMvc.perform(get(TENANTS + "/ALPHA-TMS")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(READ)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("two tenants get two organizations, and each status names only its own")
        void isolation() throws Exception {
            String a = mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_A, bodyA()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String b = mockMvc.perform(create(PlatformM2mTestTokens.valid(CREATE), KEY_B,
                            json(TENANT_B, "beta-tms", "admin@beta.ebim.test", "Beta Transportes")))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

            String orgA = JsonPath.read(a, "$.externalTenantId");
            String orgB = JsonPath.read(b, "$.externalTenantId");
            assertThat(orgA).isNotEqualTo(orgB);
            assertThat((String) JsonPath.read(a, "$.externalCompanyId"))
                    .isNotEqualTo(JsonPath.read(b, "$.externalCompanyId"));

            mockMvc.perform(get(TENANTS + "/" + TENANT_B)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(READ)))
                    .andExpect(jsonPath("$.externalTenantId").value(orgB))
                    .andExpect(jsonPath("$.resources.organizationCode").value("BETA-TMS"));

            // Each administrator reaches exactly one organization: their own.
            assertThat(repository.memberships.values())
                    .extracting(m -> m.organizationId().toString())
                    .containsExactlyInAnyOrder(orgA, orgB);
            assertThat(repository.companies.values())
                    .allSatisfy(c -> assertThat(c.organizationId().toString()).isIn(orgA, orgB))
                    .extracting(c -> c.organizationId().toString())
                    .containsExactlyInAnyOrder(orgA, orgB);
        }
    }
}
