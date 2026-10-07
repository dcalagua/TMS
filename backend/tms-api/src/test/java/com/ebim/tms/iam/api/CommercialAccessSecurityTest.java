package com.ebim.tms.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.application.CompanyContextService;
import com.ebim.tms.iam.application.MeService;
import com.ebim.tms.iam.entitlements.InMemoryEntitlementStore;
import com.ebim.tms.iam.entitlements.application.CommercialAccessGateAdapter;
import com.ebim.tms.iam.entitlements.application.CommercialEntitlementService;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.AccessFacts;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.security.CommercialAccessGate;
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
import java.util.Optional;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Commercial access through the real user chain, with the REAL gate adapter over the local snapshot
 * facts: D-14 ruling 1 (2026-09-29) - an organization whose snapshot says {@code appActive=false}, or
 * that is in PRIMARY with no snapshot yet, loses its commercial SaaS surface but keeps operating its
 * companies. Membership is still checked first, and {@code /me} is never gated.
 *
 * <p>A full operational shutdown is a separate policy that does not exist yet; the filter is the hook
 * it would use ({@code CommercialAccessFilterTest}), and no commercial fact reaches it.
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
    CommercialAccessSecurityTest.LocalSnapshot.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class CommercialAccessSecurityTest {

    private static final String ME = "/api/v1/me";
    private static final String CURRENT_COMPANY = "/api/v1/companies/current";

    /** The production adapter and service, over an in-memory store whose facts each test sets. */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalSnapshot {

        @Bean
        InMemoryEntitlementStore entitlementStore() {
            return new InMemoryEntitlementStore(EnforcementMode.SHADOW);
        }

        @Bean
        CommercialAccessGate commercialAccessGate(InMemoryEntitlementStore store) {
            return new CommercialAccessGateAdapter(new CommercialEntitlementService(store, ReceiverProfile.tms()));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEntitlementStore store;

    @BeforeEach
    void clear() {
        store.currentCompany = Optional.empty();
    }

    private static MockHttpServletRequestBuilder bearer(String path, java.util.UUID authUser) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(authUser));
    }

    @Test
    @DisplayName("appActive=false in PRIMARY withdraws the commercial surface only: the company still operates")
    void inactiveOrganizationStillOperates() throws Exception {
        store.currentCompany = Optional.of(new AccessFacts(true, EnforcementMode.PRIMARY, false));

        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.PLANNER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRIMARY with no snapshot applied yet does not refuse operation either")
    void primaryWithoutSnapshotStillOperates() throws Exception {
        store.currentCompany = Optional.of(new AccessFacts(true, EnforcementMode.PRIMARY, null));

        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.VIEWER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/me stays reachable")
    void meIsNotGated() throws Exception {
        store.currentCompany = Optional.of(new AccessFacts(true, EnforcementMode.PRIMARY, false));

        mockMvc.perform(bearer(ME, TestPrincipals.PLANNER_AUTH_USER)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("another organization is unaffected")
    void otherOrganizationsOperate() throws Exception {
        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.OUTSIDER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.SOUTH_AREQUIPA.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a company the caller does not belong to is still company-scope-forbidden: commercial state is not RBAC")
    void membershipIsCheckedFirst() throws Exception {
        store.currentCompany = Optional.of(new AccessFacts(true, EnforcementMode.PRIMARY, true));

        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.OUTSIDER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("company-scope-forbidden"));
    }
}
