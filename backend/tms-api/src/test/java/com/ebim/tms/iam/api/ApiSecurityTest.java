package com.ebim.tms.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.application.CompanyContextService;
import com.ebim.tms.iam.application.MeService;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.config.ApplicationConfig;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security contract of the API, exercised end to end through the real filter chain.
 *
 * <p>Every case here is a rule the product depends on, written as the negative first: what is
 * refused, and with which answer. The tokens are genuinely signed and genuinely verified (see
 * {@code TestJwts}); only the key source is local, so nothing contacts an authentication
 * service and no secret exists in the repository.
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
    SecurityTestConfiguration.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class ApiSecurityTest {

    private static final String ME = "/api/v1/me";
    private static final String CURRENT_COMPANY = "/api/v1/companies/current";

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a request without a token is refused, not served anonymously")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(get(ME))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("unauthenticated"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }

        @Test
        @DisplayName("a token signed by a key we do not trust is refused")
        void forgedSignatureIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.forgedFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid-token"));
        }

        @Test
        @DisplayName("an expired token is refused")
        void expiredTokenIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.expiredFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid-token"));
        }

        @Test
        @DisplayName("a token from another issuer is refused even though its signature is valid here")
        void wrongIssuerIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.wrongIssuerFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid-token"));
        }

        @Test
        @DisplayName("a token minted for another audience is refused")
        void wrongAudienceIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.wrongAudienceFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid-token"));
        }

        @Test
        @DisplayName("garbage in the Authorization header is refused")
        void malformedTokenIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, "not.a.jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid-token"));
        }

        @Test
        @DisplayName("a subject that is not a Supabase user id cannot map to a profile")
        void nonUuidSubjectIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.nonUuidSubject()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid-token"));
        }

        @Test
        @DisplayName("a valid token with no active TMS profile is refused with 403, not 401")
        void unprovisionedPrincipalIsRejected() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.validFor(TestPrincipals.UNPROVISIONED_AUTH_USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("principal-not-provisioned"));
        }

        @Test
        @DisplayName("a valid, mapped user is accepted and sees only their own companies")
        void validPrincipalIsAccepted() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value("planner@example.invalid"))
                    .andExpect(jsonPath("$.companies.length()").value(1))
                    .andExpect(jsonPath("$.companies[0].code").value("NORTH-LIMA"))
                    .andExpect(jsonPath("$.companies[0].capabilities").value(
                            org.hamcrest.Matchers.hasItems("ORDERS_MANAGE", "TRIPS_MANAGE")));
        }

        @Test
        @DisplayName("a provisioned user with no membership is authenticated but scopeless")
        void principalWithoutMembershipsIsAuthenticated() throws Exception {
            mockMvc.perform(bearer(ME, TestJwts.validFor(
                            UUID.fromString("00000000-0000-4000-8000-0000000000e5"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companies.length()").value(0));
        }
    }

    @Nested
    @DisplayName("company scope")
    class CompanyScopeSelection {

        @Test
        @DisplayName("a company-scoped endpoint without the header is a client error, not a guess")
        void missingCompanyHeaderIsRejected() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("company-scope-required"));
        }

        @Test
        @DisplayName("a company header that is not a UUID is refused")
        void malformedCompanyHeaderIsRejected() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, "the-lima-one"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("company-scope-invalid"));
        }

        @Test
        @DisplayName("the caller's own company is accepted")
        void ownCompanyIsAccepted() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("NORTH-LIMA"))
                    .andExpect(jsonPath("$.organization.code").value("NORTH"));
        }

        @Test
        @DisplayName("a company of another organization is refused")
        void companyOfAnotherOrganizationIsRejected() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.SOUTH_AREQUIPA.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));
        }

        @Test
        @DisplayName("a sibling company the caller is not a member of is refused")
        void siblingCompanyWithoutMembershipIsRejected() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_TRUJILLO.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));
        }

        @Test
        @DisplayName("an unknown company id is refused without revealing whether it exists")
        void unknownCompanyIsRejected() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, UUID.randomUUID().toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"))
                    .andExpect(jsonPath("$.detail").value(
                            "The selected company is not available to this account."));
        }

        @Test
        @DisplayName("the outsider's own company is served to the outsider and to nobody else")
        void isolationIsSymmetric() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.OUTSIDER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.SOUTH_AREQUIPA.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SOUTH-AREQUIPA"));

            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.OUTSIDER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("permissions")
    class Permissions {

        @Test
        @DisplayName("the required permission grants access")
        void permittedCallerIsAccepted() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.VIEWER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_LIMA.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("permissions are per selected company, not unioned across the caller's companies")
        void permissionsDoNotLeakBetweenCompanies() throws Exception {
            // The same user holds iam.company:read in NORTH-LIMA but not in NORTH-TRUJILLO.
            // A principal-wide union of authorities would wrongly accept this request.
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.VIEWER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_TRUJILLO.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("the denial does not name the permission that was missing")
        void denialDoesNotDiscloseTheAuthorizationModel() throws Exception {
            mockMvc.perform(bearer(CURRENT_COMPANY, TestJwts.validFor(TestPrincipals.VIEWER_AUTH_USER))
                            .header(ApiHeaders.COMPANY_ID, TestPrincipals.NORTH_TRUJILLO.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("iam.company"))));
        }
    }

    @Nested
    @DisplayName("error documents")
    class ErrorShape {

        @Test
        @DisplayName("every failure is one RFC 9457 document with a stable type, code and instance")
        void errorShapeIsUniform() throws Exception {
            mockMvc.perform(get(ME))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:tms:problem:unauthenticated"))
                    .andExpect(jsonPath("$.title").value("Authentication required"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.detail").isNotEmpty())
                    .andExpect(jsonPath("$.instance").value(ME))
                    .andExpect(jsonPath("$.code").value("unauthenticated"))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        @DisplayName("no error document carries a stack trace, an exception name or a SQL fragment")
        void errorsDoNotLeakInternals() throws Exception {
            String body = mockMvc.perform(bearer(CURRENT_COMPANY,
                            TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER)))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("Exception")
                    .doesNotContain("com.ebim.tms")
                    .doesNotContain("org.springframework")
                    .doesNotContain("SELECT");
        }

        @Test
        @DisplayName("a rejected request is still traceable: the correlation id is echoed")
        void correlationIdIsEchoed() throws Exception {
            mockMvc.perform(get(ME).header(ApiHeaders.CORRELATION_ID, "step03-trace-1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(ApiHeaders.CORRELATION_ID, "step03-trace-1"))
                    .andExpect(jsonPath("$.correlationId").value("step03-trace-1"));
        }

        @Test
        @DisplayName("a hostile correlation id is replaced rather than echoed into logs and headers")
        void hostileCorrelationIdIsReplaced() throws Exception {
            mockMvc.perform(get(ME).header(ApiHeaders.CORRELATION_ID, "bad\nvalue <script>"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(ApiHeaders.CORRELATION_ID,
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script"))));
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bearer(
            String path, String token) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
