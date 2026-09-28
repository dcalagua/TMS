package com.ebim.tms.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.application.CompanyContextService;
import com.ebim.tms.iam.application.MeService;
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

/**
 * Commercial access through the real user chain: an organization whose contract is suspended
 * ({@code appActive=false} decided by the local snapshot) cannot operate any company, whatever its
 * members' permissions - and still reaches {@code /me}, so the application can say why.
 *
 * <p>The gate here is a fake with a fixed answer per organization; what it would answer is
 * {@code CommercialAccessTest}'s and {@code CommercialEntitlementServiceTest}'s business.
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
    CommercialAccessSecurityTest.SuspendedNorth.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class CommercialAccessSecurityTest {

    private static final String ME = "/api/v1/me";
    private static final String CURRENT_COMPANY = "/api/v1/companies/current";

    /** The NORTH organization's contract is suspended; SOUTH's is live. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SuspendedNorth {

        @Bean
        CommercialAccessGate commercialAccessGate() {
            return scope -> TestPrincipals.NORTH_ORG.equals(scope.organizationId())
                    ? CommercialAccessGate.Verdict.suspended("APP_INACTIVE")
                    : CommercialAccessGate.Verdict.ALLOWED;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder bearer(String path, java.util.UUID authUser) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(authUser));
    }

    @Test
    @DisplayName("a suspended organization's company is refused with 403 commercial-access-suspended")
    void suspendedOrganizationIsRefused() throws Exception {
        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.PLANNER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("commercial-access-suspended"));
    }

    @Test
    @DisplayName("holding every permission does not help: commercial access is not RBAC")
    void permissionsDoNotOverrideIt() throws Exception {
        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.VIEWER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("commercial-access-suspended"));
    }

    @Test
    @DisplayName("/me stays reachable, so the application can explain the suspension")
    void meIsNotGated() throws Exception {
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
    @DisplayName("a company the caller does not belong to is still company-scope-forbidden, not a commercial answer")
    void membershipIsCheckedFirst() throws Exception {
        mockMvc.perform(bearer(CURRENT_COMPANY, TestPrincipals.OUTSIDER_AUTH_USER)
                        .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("company-scope-forbidden"));
    }
}
