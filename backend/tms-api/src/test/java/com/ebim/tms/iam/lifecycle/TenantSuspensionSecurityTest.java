package com.ebim.tms.iam.lifecycle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.api.CompanyContextController;
import com.ebim.tms.iam.api.MeController;
import com.ebim.tms.iam.application.CompanyContextService;
import com.ebim.tms.iam.application.MeService;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.Applied;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.CallContext;
import com.ebim.tms.iam.lifecycle.application.TenantLifecycleService;
import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.PublicApiPaths;
import com.ebim.tms.shared.security.SecurityConfig;
import com.ebim.tms.shared.security.SecurityTestConfiguration;
import com.ebim.tms.shared.security.TestJwts;
import com.ebim.tms.shared.security.TestPrincipals;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.security.TmsSecurityProperties;
import com.ebim.tms.shared.web.WebConfig;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An operational suspension through the real user chain (lifecycle.v1, ADR-021), with the REAL
 * {@link TenantLifecycleService} over an in-memory store: a suspended organization's writes are 423
 * {@code tenant-suspended}, its reads, {@code /me} and {@code /companies/current} go on and report the
 * state for the banner, another organization is untouched, and ACTIVE resumes everything.
 */
@WebMvcTest(controllers = {MeController.class, CompanyContextController.class})
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
    MeService.class,
    CompanyContextService.class,
    SecurityTestConfiguration.class,
    TenantSuspensionSecurityTest.LocalLifecycle.class,
    TenantSuspensionSecurityTest.ProbeController.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class TenantSuspensionSecurityTest {

    private static final UUID NORTH_TENANT = UUID.fromString("00000000-0000-4ccc-8000-0000000058c1");
    private static final UUID SOUTH_TENANT = UUID.fromString("00000000-0000-4ccc-8000-0000000058c2");
    private static final String PROBE = "/api/v1/lifecycle-probe";

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalLifecycle {

        @Bean
        InMemoryLifecycleStore lifecycleStore() {
            InMemoryLifecycleStore store = new InMemoryLifecycleStore();
            store.provision(NORTH_TENANT, TestPrincipals.NORTH_ORG);
            store.provision(SOUTH_TENANT, TestPrincipals.SOUTH_ORG);
            return store;
        }

        @Bean
        TenantLifecycleService tenantLifecycleService(InMemoryLifecycleStore store) {
            return new TenantLifecycleService(store);
        }
    }

    /** A company-scoped write and read, standing for every business endpoint behind the chain. */
    @RestController
    @RequestMapping(PROBE)
    static class ProbeController {

        @GetMapping
        Map<String, String> read(CompanyScope scope) {
            return Map.of("company", scope.companyId().toString());
        }

        @PostMapping
        Map<String, String> write(CompanyScope scope) {
            return Map.of("written", scope.companyId().toString());
        }

        @DeleteMapping
        Map<String, String> remove(CompanyScope scope) {
            return Map.of("deleted", scope.companyId().toString());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryLifecycleStore store;

    @BeforeEach
    void clear() {
        store.clear();
    }

    private void set(UUID tenant, UUID organization, long version, LifecycleState state, LifecycleState.Reason reason) {
        store.save(new Applied(tenant, organization, state, reason, version, Instant.parse("2026-10-13T11:00:00Z"),
                null, null), new CallContext(null, "test", "jti-" + version));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID authUser,
            UUID company) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(authUser));
        return company == null ? request : request.header(ApiHeaders.COMPANY_ID, company.toString());
    }

    @Test
    @DisplayName("a suspended organization's write is 423 tenant-suspended with error TENANT_SUSPENDED and reason")
    void writeIsRefused() throws Exception {
        set(NORTH_TENANT, TestPrincipals.NORTH_ORG, 1, LifecycleState.SUSPENDED, LifecycleState.Reason.NON_PAYMENT);

        mockMvc.perform(as(post(PROBE), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.NORTH_LIMA))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("tenant-suspended"))
                .andExpect(jsonPath("$.error").value("TENANT_SUSPENDED"))
                .andExpect(jsonPath("$.reason").value("NON_PAYMENT"))
                .andExpect(jsonPath("$.written").doesNotExist());
        mockMvc.perform(as(delete(PROBE), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.NORTH_LIMA))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("reads of a suspended organization go on")
    void readsGoOn() throws Exception {
        set(NORTH_TENANT, TestPrincipals.NORTH_ORG, 1, LifecycleState.SUSPENDED, LifecycleState.Reason.MANUAL);

        mockMvc.perform(as(get(PROBE), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.NORTH_LIMA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value(TestPrincipals.NORTH_LIMA.toString()));
    }

    @Test
    @DisplayName("/me and /companies/current stay reachable and carry the operational state for the banner")
    void meReportsTheState() throws Exception {
        set(NORTH_TENANT, TestPrincipals.NORTH_ORG, 1, LifecycleState.SUSPENDED, LifecycleState.Reason.NON_PAYMENT);

        mockMvc.perform(as(get("/api/v1/me"), TestPrincipals.PLANNER_AUTH_USER, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].operationalStatus.state").value("SUSPENDED"))
                .andExpect(jsonPath("$.companies[0].operationalStatus.reason").value("NON_PAYMENT"))
                .andExpect(jsonPath("$.companies[0].operationalStatus.appliedAt").value("2026-10-13T11:00:00Z"));
        mockMvc.perform(as(get("/api/v1/companies/current"), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.NORTH_LIMA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationalStatus.state").value("SUSPENDED"));
        mockMvc.perform(as(get("/api/v1/me"), TestPrincipals.OUTSIDER_AUTH_USER, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].operationalStatus.state").value("ACTIVE"))
                .andExpect(jsonPath("$.companies[0].operationalStatus.reason").isEmpty());
    }

    @Test
    @DisplayName("another organization keeps writing")
    void otherOrganizationsWrite() throws Exception {
        set(NORTH_TENANT, TestPrincipals.NORTH_ORG, 1, LifecycleState.SUSPENDED, LifecycleState.Reason.TERMINATED);

        mockMvc.perform(as(post(PROBE), TestPrincipals.OUTSIDER_AUTH_USER, TestPrincipals.SOUTH_AREQUIPA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(TestPrincipals.SOUTH_AREQUIPA.toString()));
    }

    @Test
    @DisplayName("ACTIVE resumes writes without any other intervention")
    void reactivationResumes() throws Exception {
        set(NORTH_TENANT, TestPrincipals.NORTH_ORG, 1, LifecycleState.SUSPENDED, LifecycleState.Reason.NON_PAYMENT);
        mockMvc.perform(as(post(PROBE), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.NORTH_LIMA))
                .andExpect(status().isLocked());

        set(NORTH_TENANT, TestPrincipals.NORTH_ORG, 2, LifecycleState.ACTIVE, null);
        mockMvc.perform(as(post(PROBE), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.NORTH_LIMA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("membership is still checked first: a suspension never turns into access")
    void membershipFirst() throws Exception {
        set(SOUTH_TENANT, TestPrincipals.SOUTH_ORG, 1, LifecycleState.SUSPENDED, LifecycleState.Reason.MANUAL);

        mockMvc.perform(as(post(PROBE), TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.SOUTH_AREQUIPA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("company-scope-forbidden"));
    }
}
