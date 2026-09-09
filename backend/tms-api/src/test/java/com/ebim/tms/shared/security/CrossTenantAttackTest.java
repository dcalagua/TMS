package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.orders.api.OrderController;
import com.ebim.tms.orders.application.OrderService;
import com.ebim.tms.orders.infrastructure.TransportOrderLineRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.planning.api.TripController;
import com.ebim.tms.planning.application.StopEtaService;
import com.ebim.tms.planning.application.TripExceptionService;
import com.ebim.tms.planning.application.TripExecutionService;
import com.ebim.tms.planning.application.TripService;
import com.ebim.tms.planning.application.TripStopExecutionService;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OrderFulfillmentPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import com.ebim.tms.shared.web.WebConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cross-tenant access, attempted against real business endpoints and refused.
 *
 * <h2>Why this exists beside {@code ApiSecurityTest}</h2>
 *
 * <p>{@code ApiSecurityTest} proves the header cannot select a company the caller has no
 * membership in, but it drives {@code /me} and {@code /companies/current} - endpoints that own
 * no business row. The complementary proof, "a tenant cannot reach another tenant's <em>data</em>
 * by naming its id", lived only in {@code OrderApiIntegrationTest} and its siblings, and every
 * one of those is {@code @EnabledIf(DockerAvailability)}. On a machine without Docker - which is
 * the machine this was written on - nothing executable asserted it at all.
 *
 * <p>So this suite runs the same attacks through the production filter chain with no database:
 * real {@link SecurityConfig}, real {@link CompanyScopeFilter}, real method security, real
 * {@link OrderService}, genuinely signed and genuinely verified tokens. Only the repositories and
 * the ports are mocks, and that is precisely what makes the assertion sharp: a mock records the
 * arguments it was called with, so the test can state what the database <em>would have been
 * asked</em>, which is the fact the tenancy property actually rests on.
 *
 * <h2>The two shapes of the attack</h2>
 *
 * <ol>
 *   <li><b>Select another tenant.</b> Send {@code X-Company-Id} naming a company the caller is
 *       not a member of. Refused in the filter - and the repository is never touched, which is
 *       the part worth asserting: the request dies before anything can leak.</li>
 *   <li><b>Name another tenant's row.</b> Send a perfectly valid header for your own company and
 *       a resource id belonging to somebody else. Nothing refuses this at the door, and nothing
 *       should: the defence is that the query carries the caller's company as a predicate, so the
 *       row is simply not there. Each test below captures both arguments and asserts the company
 *       is the caller's, never the row's.</li>
 * </ol>
 *
 * <p>RLS (ADR-005) would catch the second shape at the database as well. It is defence in depth
 * and deliberately not the authorization: this suite is the layer CLAUDE.md says decides.
 */
@WebMvcTest(controllers = {OrderController.class, TripController.class})
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
    OrderService.class,
    SecurityTestConfiguration.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class CrossTenantAttackTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String TRIPS = "/api/v1/planning/trips";

    /**
     * A row that belongs to SOUTH-AREQUIPA. Its value is irrelevant and that is the point: the
     * attacker is assumed to know it - leaked in a screenshot, guessed, or read from an export.
     */
    private static final UUID FOREIGN_ROW = UUID.fromString("00000000-0000-4000-8000-0000000000d9");

    @Autowired
    private MockMvc mockMvc;

    // OrderService is real; everything behind it is a mock that records what it was asked.
    @MockitoBean
    private TransportOrderRepository transportOrderRepository;
    @MockitoBean
    private TransportOrderLineRepository transportOrderLineRepository;
    @MockitoBean
    private OriginLookupPort originLookupPort;
    @MockitoBean
    private DestinationLookupPort destinationLookupPort;
    @MockitoBean
    private OrderFulfillmentPort orderFulfillmentPort;
    @MockitoBean
    private CompanySettingsPort companySettingsPort;
    @MockitoBean
    private AuditActorProvider auditActorProvider;
    @MockitoBean
    private AuditRecorder auditRecorder;

    // A second module, to show the property is the chain's and not one service's habit.
    @MockitoBean
    private TripService tripService;
    @MockitoBean
    private StopEtaService stopEtaService;
    @MockitoBean
    private TripExecutionService tripExecutionService;
    @MockitoBean
    private TripStopExecutionService tripStopExecutionService;
    @MockitoBean
    private TripExceptionService tripExceptionService;

    @Nested
    @DisplayName("selecting another tenant with the company header")
    class HeaderManipulation {

        @Test
        @DisplayName("a company of another organization is refused before the repository is reached")
        void foreignCompanyHeaderIsRefusedAtTheDoor() throws Exception {
            mockMvc.perform(as(planner(), get(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.SOUTH_AREQUIPA))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));

            verifyNoInteractions(transportOrderRepository);
        }

        @Test
        @DisplayName("a sibling company in the caller's own organization is refused just the same")
        void siblingCompanyHeaderIsRefused() throws Exception {
            // Same organization, no membership. Tenancy is the company, not the organization.
            mockMvc.perform(as(planner(), get(ORDERS), TestPrincipals.NORTH_TRUJILLO))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));

            verifyNoInteractions(transportOrderRepository);
        }

        @Test
        @DisplayName("a token carrying no membership at all reaches no company")
        void principalWithoutMembershipsReachesNothing() throws Exception {
            String newcomer = TestJwts.validFor(UUID.fromString("00000000-0000-4000-8000-0000000000e5"));
            mockMvc.perform(as(newcomer, get(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));

            verifyNoInteractions(transportOrderRepository);
        }

        @Test
        @DisplayName("omitting the header selects no company rather than defaulting to one")
        void missingHeaderDoesNotDefaultToTheCallersOnlyCompany() throws Exception {
            // The caller has exactly one membership, so "just use it" would look harmless here and
            // would be the habit that picks the wrong tenant for a caller who has three.
            //
            // The answer is 400 rather than 403 because argument resolution precedes the method
            // invocation the @PreAuthorize proxy guards, so CompanyScopeArgumentResolver refuses
            // first. That ordering is the good one and worth pinning: the caller is told what is
            // missing instead of being told they lack a permission they in fact hold.
            mockMvc.perform(get(ORDERS + "/" + FOREIGN_ROW)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + planner()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("company-scope-required"));

            verifyNoInteractions(transportOrderRepository);
        }
    }

    @Nested
    @DisplayName("naming another tenant's row with a valid header")
    class ResourceIdManipulation {

        @Test
        @DisplayName("reading a foreign order asks the database for it inside the caller's company")
        void readingAForeignOrderIsScopedToTheCaller() throws Exception {
            mockMvc.perform(as(planner(), get(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA))
                    .andExpect(status().isNotFound());

            ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<UUID> company = ArgumentCaptor.forClass(UUID.class);
            verify(transportOrderRepository).findByIdAndCompanyId(id.capture(), company.capture());

            assertThat(id.getValue())
                    .as("the id the attacker supplied is passed through - it is not the defence")
                    .isEqualTo(FOREIGN_ROW);
            assertThat(company.getValue())
                    .as("""
                            the company predicate is the caller's resolved membership, never the \
                            company the row belongs to; this is the whole of the defence, so if it \
                            ever reads SOUTH_AREQUIPA here the tenant boundary is gone""")
                    .isEqualTo(TestPrincipals.NORTH_LIMA);
        }

        @Test
        @DisplayName("the refusal is 404, which does not confirm that the row exists elsewhere")
        void theRefusalDoesNotDiscloseExistence() throws Exception {
            String body = mockMvc.perform(as(planner(), get(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("an id that exists in another tenant and one that exists nowhere must be "
                            + "indistinguishable, or the API is an existence oracle for other tenants")
                    .contains("Order not found.")
                    .doesNotContain(TestPrincipals.SOUTH_AREQUIPA.toString());
        }

        @Test
        @DisplayName("updating a foreign order writes nothing")
        void updatingAForeignOrderWritesNothing() throws Exception {
            mockMvc.perform(as(planner(), put(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody()))
                    .andExpect(status().isNotFound());

            verify(transportOrderRepository).findByIdAndCompanyId(eq(FOREIGN_ROW), eq(TestPrincipals.NORTH_LIMA));
            verify(transportOrderRepository, never()).save(any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("cancelling a foreign order writes nothing")
        void cancellingAForeignOrderWritesNothing() throws Exception {
            mockMvc.perform(as(planner(), post(ORDERS + "/" + FOREIGN_ROW + "/cancel"), TestPrincipals.NORTH_LIMA))
                    .andExpect(status().isNotFound());

            verify(transportOrderRepository).findByIdAndCompanyId(eq(FOREIGN_ROW), eq(TestPrincipals.NORTH_LIMA));
            verify(transportOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("a second module behaves the same: the trip service is handed the caller's company")
        void aForeignTripIsLookedUpInTheCallersCompany() throws Exception {
            mockMvc.perform(as(planner(), get(TRIPS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA))
                    .andExpect(status().isOk());

            ArgumentCaptor<CompanyScope> scope = ArgumentCaptor.forClass(CompanyScope.class);
            verify(tripService).get(scope.capture(), eq(FOREIGN_ROW));

            assertThat(scope.getValue().companyId())
                    .as("a service can only ever be handed a CompanyScope the filter resolved, so "
                            + "the tenant of a request is never a value the caller chose")
                    .isEqualTo(TestPrincipals.NORTH_LIMA);
        }
    }

    @Nested
    @DisplayName("putting another tenant in the request body")
    class BodyManipulation {

        @Test
        @DisplayName("companyId and organizationId in the body are inert - the scope decides")
        void tenantFieldsInTheBodyAreIgnored() throws Exception {
            // No write DTO in this codebase declares a tenant field, so Jackson drops these two
            // silently. The risk that leaves is exactly this: a payload that *looks* accepted.
            // The assertion is therefore not the status code but which company the lookup used.
            String hostile = """
                    {
                      "companyId": "%s",
                      "organizationId": "%s",
                      "originId": "%s",
                      "destinationId": "%s",
                      "serviceDate": "2026-01-15",
                      "priority": "NORMAL",
                      "lines": []
                    }
                    """.formatted(TestPrincipals.SOUTH_AREQUIPA, TestPrincipals.SOUTH_ORG,
                    FOREIGN_ROW, FOREIGN_ROW);

            mockMvc.perform(as(planner(), post(ORDERS), TestPrincipals.NORTH_LIMA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(hostile))
                    .andExpect(status().isBadRequest());

            ArgumentCaptor<UUID> company = ArgumentCaptor.forClass(UUID.class);
            verify(originLookupPort).findActiveInCompany(eq(FOREIGN_ROW), company.capture());

            assertThat(company.getValue())
                    .as("the master-data lookup that decides whether this order may exist is scoped "
                            + "by the resolved membership, not by the company named in the payload")
                    .isEqualTo(TestPrincipals.NORTH_LIMA);
            verify(transportOrderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("claiming another tenant in the token itself")
    class ClaimManipulation {

        @Test
        @DisplayName("a company_id claim naming another tenant grants nothing")
        void aTenantClaimDoesNotSelectATenant() throws Exception {
            String hostile = TestJwts.withHostileClaims(
                    TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.SOUTH_AREQUIPA);

            mockMvc.perform(as(hostile, get(ORDERS), TestPrincipals.SOUTH_AREQUIPA))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));

            verifyNoInteractions(transportOrderRepository);
        }

        @Test
        @DisplayName("a permissions claim and role=service_role do not become authorities")
        void aPermissionClaimDoesNotBecomeAnAuthority() throws Exception {
            // The viewer holds orders.order:read in NORTH-LIMA. The token asserts
            // orders.order:manage and SUPER_ADMIN. Authorities come from tms.membership, so the
            // write endpoint stays shut.
            String hostile = TestJwts.withHostileClaims(
                    TestPrincipals.VIEWER_AUTH_USER, TestPrincipals.NORTH_LIMA);

            mockMvc.perform(as(hostile, put(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            verifyNoInteractions(transportOrderRepository);
        }

        @Test
        @DisplayName("the hostile token is otherwise authentic - it is read, and only its subject is used")
        void theHostileTokenIsGenuinelyAccepted() throws Exception {
            // Without this the two tests above would also pass if the token were simply rejected,
            // which would prove the signature check and nothing about claim handling.
            String hostile = TestJwts.withHostileClaims(
                    TestPrincipals.PLANNER_AUTH_USER, TestPrincipals.SOUTH_AREQUIPA);

            mockMvc.perform(as(hostile, get(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA))
                    .andExpect(status().isNotFound());

            verify(transportOrderRepository).findByIdAndCompanyId(eq(FOREIGN_ROW), eq(TestPrincipals.NORTH_LIMA));
        }
    }

    @Nested
    @DisplayName("permissions inside a company the caller does belong to")
    class PermissionEscalation {

        @Test
        @DisplayName("read access in a company is not write access in it")
        void readAccessDoesNotBecomeWriteAccess() throws Exception {
            // The viewer holds orders.order:read in NORTH-LIMA and no manage anywhere.
            mockMvc.perform(as(viewer(), put(ORDERS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_LIMA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            verifyNoInteractions(transportOrderRepository);
        }

        @Test
        @DisplayName("permissions held in one company do not travel to another the caller also belongs to")
        void permissionsDoNotTravelBetweenTheCallersOwnCompanies() throws Exception {
            // The viewer is a member of both NORTH-LIMA and NORTH-TRUJILLO but holds
            // planning.trip:read in neither, so the trip endpoint is closed in both. What this
            // asserts is that being a member of two companies unions nothing.
            mockMvc.perform(as(viewer(), get(TRIPS + "/" + FOREIGN_ROW), TestPrincipals.NORTH_TRUJILLO))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            verifyNoInteractions(tripService);
        }
    }

    private static String planner() {
        return TestJwts.validFor(TestPrincipals.PLANNER_AUTH_USER);
    }

    private static String viewer() {
        return TestJwts.validFor(TestPrincipals.VIEWER_AUTH_USER);
    }

    private static String orderBody() {
        return """
                {
                  "originId": "%s",
                  "destinationId": "%s",
                  "serviceDate": "2026-01-15",
                  "priority": "NORMAL",
                  "version": 0,
                  "lines": []
                }
                """.formatted(FOREIGN_ROW, FOREIGN_ROW);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request, UUID company) {
        return request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(ApiHeaders.COMPANY_ID, company.toString());
    }
}
