package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.integration.application.IntegrationAuthenticationService;
import com.ebim.tms.integration.application.IntegrationInboxService;
import com.ebim.tms.integration.application.IntegrationLocationService;
import com.ebim.tms.integration.application.IntegrationOrderService;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.domain.IntegrationClient;
import com.ebim.tms.integration.domain.IntegrationRequest;
import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.integration.infrastructure.IntegrationClientRepository;
import com.ebim.tms.integration.infrastructure.IntegrationRequestRepository;
import com.ebim.tms.integration.security.IntegrationSecurityConfig;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.reference.IntakeOutcome;
import com.ebim.tms.shared.reference.LocationIntakeCommand;
import com.ebim.tms.shared.reference.LocationIntakePort;
import com.ebim.tms.shared.reference.LocationIntakeResult;
import com.ebim.tms.shared.reference.OrderIntakeCommand;
import com.ebim.tms.shared.reference.OrderIntakePort;
import com.ebim.tms.shared.reference.OrderIntakeResult;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.web.WebConfig;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The inbound integration API exercised over real HTTP, through the real security chain.
 *
 * <h2>What this class is for</h2>
 *
 * <p>{@code IntegrationAuthenticationServiceTest} already proves that the authenticator resolves a
 * company from the credential. That is a statement about one class. This one is the statement
 * about the <em>system</em>: a request arrives on the wire carrying a credential of company A and
 * whatever headers its sender chose, and what reaches the masterdata and orders modules is company
 * A's scope - always, and with no way for the caller to influence it.
 *
 * <p>The seam is deliberately at {@link LocationIntakePort} / {@link OrderIntakePort}: the ports
 * are the only door the integration module has into the business modules, so capturing the
 * {@link CompanyScope} handed to them captures every tenant decision the request made. A fake
 * records what it was given instead of writing to a database, which is what lets this run on every
 * build rather than only where Docker is available.
 *
 * <p>Everything except those two ports and the three repositories is the production wiring: the
 * real {@link IntegrationSecurityConfig} chain, the real filter, the real
 * {@code @PreAuthorize} evaluation, the real problem-document handler.
 */
@WebMvcTest(
        controllers = {
            IntegrationLocationController.class,
            IntegrationOrderController.class,
            IntegrationIdentityController.class
        },
        // TmsJwtAuthenticationConverter is a Converter, so the slice would scan it in and then
        // demand the whole user-facing identity stack. It belongs to the other security chain and
        // has nothing to do with a machine credential - excluding it is the point being made.
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = TmsJwtAuthenticationConverter.class))
@Import({
    ApplicationConfig.class,
    WebConfig.class,
    IntegrationSecurityConfig.class,
    IntegrationWebConfig.class,
    IntegrationAuthenticationService.class,
    IntegrationLocationService.class,
    IntegrationOrderService.class,
    IntegrationRequestExecutor.class,
    IntegrationInboxService.class,
    ApiExceptionResponder.class,
    ApiExceptionHandler.class,
    TmsAuthenticationEntryPoint.class,
    TmsAccessDeniedHandler.class,
    IntegrationApiTenancyTest.Fakes.class
})
@ActiveProfiles("test")
class IntegrationApiTenancyTest {

    private static final String LOCATIONS = "/integration/v1/locations";
    private static final String ORDERS = "/integration/v1/orders";
    private static final String PING = "/integration/v1/ping";

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("77777777-0000-4000-8000-0000000000a1");
    private static final UUID COMPANY_B = UUID.fromString("77777777-0000-4000-8000-0000000000b1");
    private static final UUID ACTOR = UUID.fromString("77777777-0000-4000-8000-0000000000e1");

    private static final String LOCATION_BODY = """
            {
              "code": "ST-4711",
              "name": "Acme Store Miraflores",
              "type": "STORE",
              "roles": ["DESTINATION"],
              "country": "PE",
              "timeZone": "America/Lima",
              "externalSystem": "ACME-ERP",
              "externalReference": "4711"
            }
            """;

    private static final String ORDER_BODY = """
            {
              "externalSource": "ACME-ERP",
              "externalReference": "SO-2026-000123",
              "originCode": "CD-LIMA",
              "destinationCode": "ST-4711",
              "serviceDate": "2026-08-25",
              "priority": "NORMAL",
              "declaredWeightKg": 1560.0
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubClients clients;

    @Autowired
    private RecordingLocationIntake locationIntake;

    @Autowired
    private RecordingOrderIntake orderIntake;

    /** A credential of company A holding both write scopes; the default caller of most tests. */
    private Credential partnerOfA;

    @BeforeEach
    void issueCredentials() {
        clients.reset();
        locationIntake.reset();
        orderIntake.reset();
        partnerOfA = clients.issue(COMPANY_A, IntegrationScope.LOCATION_WRITE, IntegrationScope.ORDER_WRITE);
    }

    // ---------------------------------------------------------------------
    // Tenancy
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("a location written by company A's credential lands in company A")
        void locationLandsInTheCredentialsCompany() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content(LOCATION_BODY))
                    .andExpect(status().isCreated());

            assertThat(locationIntake.scopes())
                    .as("the tenant of the write is the credential's, and nothing else could have set it")
                    .containsExactly(COMPANY_A);
        }

        @Test
        @DisplayName("an order written by company A's credential lands in company A")
        void orderLandsInTheCredentialsCompany() throws Exception {
            mockMvc.perform(authenticated(post(ORDERS), partnerOfA).content(ORDER_BODY))
                    .andExpect(status().isCreated());

            assertThat(orderIntake.scopes()).containsExactly(COMPANY_A);
        }

        @Test
        @DisplayName("company B's credential never reaches company A, whatever it sends")
        void anotherCompanysCredentialCannotReachCompanyA() throws Exception {
            Credential partnerOfB = clients.issue(COMPANY_B, IntegrationScope.LOCATION_WRITE);

            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfB).content(LOCATION_BODY))
                    .andExpect(status().isCreated());

            assertThat(locationIntake.scopes())
                    .as("the same payload, the same code, a different credential: a different tenant")
                    .containsExactly(COMPANY_B);
        }

        @Test
        @DisplayName("X-Company-Id naming another company is refused, not ignored")
        void crossCompanyHeaderIsRefused() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA)
                            .header(ApiHeaders.COMPANY_ID, COMPANY_B.toString())
                            .content(LOCATION_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("company-scope-forbidden"));

            assertThat(locationIntake.scopes())
                    .as("the request must not reach the business module at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("X-Company-Id naming the credential's own company is accepted and changes nothing")
        void matchingCompanyHeaderIsHarmless() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA)
                            .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString())
                            .content(LOCATION_BODY))
                    .andExpect(status().isCreated());

            assertThat(locationIntake.scopes()).containsExactly(COMPANY_A);
        }

        @Test
        @DisplayName("a malformed X-Company-Id is refused rather than silently dropped")
        void malformedCompanyHeaderIsRefused() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA)
                            .header(ApiHeaders.COMPANY_ID, "not-a-uuid")
                            .content(LOCATION_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("company-scope-invalid"));

            assertThat(locationIntake.scopes()).isEmpty();
        }

        @Test
        @DisplayName("ping reports the credential's own company and never another")
        void pingReportsTheBoundCompany() throws Exception {
            Credential partnerOfB = clients.issue(COMPANY_B, IntegrationScope.ORDER_WRITE);

            mockMvc.perform(authenticated(get(PING), partnerOfA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyCode").value("CO-A"));

            mockMvc.perform(authenticated(get(PING), partnerOfB)
                            .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------------
    // Authentication
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a request with no credential is refused, not served anonymously")
        void missingCredentialIsRefused() throws Exception {
            mockMvc.perform(post(LOCATIONS).contentType(MediaType.APPLICATION_JSON).content(LOCATION_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("unauthenticated"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

            assertThat(locationIntake.scopes()).isEmpty();
        }

        @Test
        @DisplayName("the rejection tells a partner about their credential, not about Supabase")
        void rejectionSpeaksTheRightVocabulary() throws Exception {
            String detail = mockMvc.perform(post(LOCATIONS)
                            .contentType(MediaType.APPLICATION_JSON).content(LOCATION_BODY))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(detail)
                    .as("a partner integration has no Supabase token and must never be told to get one")
                    .doesNotContain("Supabase")
                    .contains("integration credential");
        }

        @Test
        @DisplayName("a wrong secret is refused, and answers exactly as an unknown client id does")
        void wrongSecretIsIndistinguishableFromAnUnknownClient() throws Exception {
            String wrongSecret = bodyOf(post(LOCATIONS), partnerOfA.clientId(), IntegrationSecrets.newSecret());
            String unknownClient = bodyOf(post(LOCATIONS), IntegrationSecrets.newClientId(),
                    IntegrationSecrets.newSecret());

            assertThat(withoutVolatileFields(wrongSecret))
                    .as("distinguishable answers would let an attacker enumerate client ids")
                    .isEqualTo(withoutVolatileFields(unknownClient));
        }

        @Test
        @DisplayName("a revoked credential stops working immediately")
        void revokedCredentialIsRefused() throws Exception {
            clients.revoke(partnerOfA);

            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content(LOCATION_BODY))
                    .andExpect(status().isUnauthorized());

            assertThat(locationIntake.scopes()).isEmpty();
        }

        @Test
        @DisplayName("a credential whose company has been deactivated stops working")
        void deactivatedCompanyClosesTheDoor() throws Exception {
            clients.deactivateCompany(COMPANY_A);

            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content(LOCATION_BODY))
                    .andExpect(status().isUnauthorized());

            assertThat(locationIntake.scopes()).isEmpty();
        }

        @Test
        @DisplayName("a token that is not in the clientId.secret form never reaches the database")
        void malformedTokenIsRefused() throws Exception {
            mockMvc.perform(post(LOCATIONS)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-credential")
                            .contentType(MediaType.APPLICATION_JSON).content(LOCATION_BODY))
                    .andExpect(status().isUnauthorized());

            assertThat(clients.lookups())
                    .as("a malformed token is refused on shape, before any lookup")
                    .isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Authorization
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("scopes")
    class Scopes {

        @Test
        @DisplayName("a location-only credential cannot post an order")
        void scopeIsEnforcedPerEndpoint() throws Exception {
            Credential locationOnly = clients.issue(COMPANY_A, IntegrationScope.LOCATION_WRITE);

            mockMvc.perform(authenticated(post(ORDERS), locationOnly).content(ORDER_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            assertThat(orderIntake.scopes()).isEmpty();
        }

        @Test
        @DisplayName("an order-only credential cannot post a location")
        void theOtherDirectionIsClosedToo() throws Exception {
            Credential orderOnly = clients.issue(COMPANY_A, IntegrationScope.ORDER_WRITE);

            mockMvc.perform(authenticated(post(LOCATIONS), orderOnly).content(LOCATION_BODY))
                    .andExpect(status().isForbidden());

            assertThat(locationIntake.scopes()).isEmpty();
        }

        @Test
        @DisplayName("the authorities of an integration credential carry no user permissions")
        void noUserPermissionsAreGranted() throws Exception {
            mockMvc.perform(authenticated(get(PING), partnerOfA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scopes").isArray())
                    .andExpect(jsonPath("$.scopes[0]").value("integration.location:write"))
                    .andExpect(jsonPath("$.scopes[1]").value("integration.order:write"));
        }
    }

    // ---------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the same key with the same payload replays the first answer without re-executing")
        void replayDoesNotExecuteTwice() throws Exception {
            mockMvc.perform(authenticated(post(ORDERS), partnerOfA)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "acme-so-2026-000123")
                            .content(ORDER_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(ApiHeaders.IDEMPOTENT_REPLAY, "false"));

            mockMvc.perform(authenticated(post(ORDERS), partnerOfA)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "acme-so-2026-000123")
                            .content(ORDER_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(ApiHeaders.IDEMPOTENT_REPLAY, "true"))
                    .andExpect(jsonPath("$.orderNumber").value("ORD-2026-000512"));

            assertThat(orderIntake.scopes())
                    .as("the second delivery must be answered from the inbox, not re-run")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a key reused for a different payload is refused rather than answered wrongly")
        void reusedKeyWithADifferentPayloadIsRefused() throws Exception {
            mockMvc.perform(authenticated(post(ORDERS), partnerOfA)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "acme-so-2026-000123")
                            .content(ORDER_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(authenticated(post(ORDERS), partnerOfA)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "acme-so-2026-000123")
                            .content(ORDER_BODY.replace("SO-2026-000123", "SO-2026-000999")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        }

        @Test
        @DisplayName("a key belongs to its own company: company B replaying company A's key executes normally")
        void keysDoNotCrossCompanies() throws Exception {
            Credential partnerOfB = clients.issue(COMPANY_B, IntegrationScope.ORDER_WRITE);

            mockMvc.perform(authenticated(post(ORDERS), partnerOfA)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "shared-key-value")
                            .content(ORDER_BODY))
                    .andExpect(status().isCreated());

            mockMvc.perform(authenticated(post(ORDERS), partnerOfB)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "shared-key-value")
                            .content(ORDER_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(ApiHeaders.IDEMPOTENT_REPLAY, "false"));

            assertThat(orderIntake.scopes())
                    .as("company B's delivery must not be answered from company A's inbox row")
                    .containsExactly(COMPANY_A, COMPANY_B);
        }

        @Test
        @DisplayName("a malformed Idempotency-Key is refused before any work happens")
        void malformedKeyIsRefused() throws Exception {
            mockMvc.perform(authenticated(post(ORDERS), partnerOfA)
                            .header(ApiHeaders.IDEMPOTENCY_KEY, "short")
                            .content(ORDER_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));

            assertThat(orderIntake.scopes()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // Inbox
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("inbox")
    class Inbox {

        @Test
        @DisplayName("an accepted delivery is recorded against the credential's own company")
        void successIsRecorded() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content(LOCATION_BODY))
                    .andExpect(status().isCreated());

            assertThat(clients.inbox()).hasSize(1);
            IntegrationRequest recorded = clients.inbox().get(0);
            assertThat(recorded.companyId()).isEqualTo(COMPANY_A);
            assertThat(recorded.operation()).isEqualTo("location.upsert");
            assertThat(recorded.payloadHash()).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("a rejected delivery is recorded too, so a failure is answerable afterwards")
        void rejectionIsRecorded() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA)
                            .content(LOCATION_BODY.replace("\"timeZone\": \"America/Lima\",", "\"timeZone\": \"\",")))
                    .andExpect(status().isBadRequest());

            assertThat(clients.inbox()).hasSize(1);
            assertThat(clients.inbox().get(0).companyId()).isEqualTo(COMPANY_A);
        }

        @Test
        @DisplayName("an unparseable body is refused without echoing the parser's message")
        void unreadableBodyIsRefusedCleanly() throws Exception {
            String body = mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content("{ not json"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("a parser message names Java types and byte offsets a partner cannot act on")
                    .doesNotContain("com.ebim.tms")
                    .doesNotContain("Exception");
            assertThat(clients.inbox())
                    .as("a body that cannot be parsed has no payload to fingerprint and no operation "
                            + "context beyond its path, so it is answered and logged rather than inboxed")
                    .isEmpty();
        }

        @Test
        @DisplayName("a recorded rejection summarises the fields, never an exception or a stack frame")
        void rejectionSummaryIsSanitised() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA)
                            .content(LOCATION_BODY.replace("\"timeZone\": \"America/Lima\",", "\"timeZone\": \"\",")))
                    .andExpect(status().isBadRequest());

            assertThat(clients.inbox().get(0).errorSummary())
                    .contains("timeZone")
                    .doesNotContain("Exception")
                    .doesNotContain("com.ebim.tms");
        }

        @Test
        @DisplayName("no inbox row ever holds credential material")
        void noCredentialMaterialIsStored() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content(LOCATION_BODY))
                    .andExpect(status().isCreated());

            assertThat(clients.inbox()).allSatisfy(row -> assertThat(row.toString())
                    .doesNotContain(partnerOfA.secret())
                    .doesNotContain(IntegrationSecrets.SECRET_PREFIX));
        }

        @Test
        @DisplayName("the raw payload is not retained unless the deployment opts in")
        void payloadIsNotRetainedByDefault() throws Exception {
            mockMvc.perform(authenticated(post(LOCATIONS), partnerOfA).content(LOCATION_BODY))
                    .andExpect(status().isCreated());

            assertThat(clients.inbox().get(0).payloadSnapshot())
                    .as("tms.integration.retain-payloads defaults to false, and the default is the decision")
                    .isNull();
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, Credential credential) {
        return request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential.bearerToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String bodyOf(MockHttpServletRequestBuilder request, String clientId, String secret) throws Exception {
        return mockMvc.perform(request
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + IntegrationSecrets.toBearerToken(clientId, secret))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOCATION_BODY))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    /** Drops the two members that legitimately differ between two responses to the same failure. */
    private static String withoutVolatileFields(String problemJson) {
        return problemJson
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"\"")
                .replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"\"");
    }

    /** What a partner configures: the public half, the secret half, and the token they send. */
    private record Credential(String clientId, String secret) {
        String bearerToken() {
            return IntegrationSecrets.toBearerToken(clientId, secret);
        }
    }

    /**
     * The five beans this test replaces, and nothing else.
     *
     * <p>Three are repositories, which stand in for tables. The other two are the module's own
     * ports into masterdata and orders - the seam that makes the tenancy assertions possible,
     * because a scope handed to a port is a scope that would have reached the database.
     */
    @TestConfiguration(proxyBeanMethods = false)
    // Production takes both from SecurityConfig, which this slice deliberately does not import:
    // it would drag in the Supabase JWT stack that has no part in a machine-credential request.
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Fakes {

        @Bean
        StubClients stubClients() {
            return new StubClients();
        }

        @Bean
        IntegrationClientRepository integrationClientRepository(StubClients clients) {
            return clients.clientRepository();
        }

        @Bean
        IntegrationRequestRepository integrationRequestRepository(StubClients clients) {
            return clients.requestRepository();
        }

        @Bean
        CompanyScopeLoader companyScopeLoader(StubClients clients) {
            return clients.companyScopeLoader();
        }

        @Bean
        RecordingLocationIntake recordingLocationIntake() {
            return new RecordingLocationIntake();
        }

        @Bean
        RecordingOrderIntake recordingOrderIntake() {
            return new RecordingOrderIntake();
        }
    }

    /**
     * An in-memory stand-in for {@code integration_client}, {@code integration_request} and the
     * company scope loader.
     *
     * <p>The idempotency store is keyed by {@code (company, client, operation, key)} - the same
     * columns as {@code uq_integration_request_idempotency} - which is what lets
     * {@link Idempotency#keysDoNotCrossCompanies} be a real assertion rather than a restatement of
     * the fake's own shape.
     */
    static final class StubClients {

        private final Map<String, IntegrationClient> byClientId = new ConcurrentHashMap<>();
        private final Map<UUID, String> secretsByClient = new ConcurrentHashMap<>();
        private final List<IntegrationRequest> inbox = new ArrayList<>();
        private final java.util.Set<UUID> inactiveCompanies = ConcurrentHashMap.newKeySet();
        private int lookups;

        void reset() {
            byClientId.clear();
            secretsByClient.clear();
            inbox.clear();
            inactiveCompanies.clear();
            lookups = 0;
        }

        Credential issue(UUID companyId, IntegrationScope... scopes) {
            String secret = IntegrationSecrets.newSecret();
            IntegrationClient client = new IntegrationClient(companyId, IntegrationSecrets.newClientId(),
                    "Partner of " + companyId, null, IntegrationSecrets.hash(secret), ACTOR);
            client.replaceScopes(List.of(scopes), ACTOR);
            // The entity's id is assigned by the persistence provider in production; the inbox
            // needs one, and it must be distinct per credential for the idempotency key scoping.
            assignId(client, UUID.randomUUID());
            byClientId.put(client.clientId(), client);
            secretsByClient.put(client.id(), secret);
            return new Credential(client.clientId(), secret);
        }

        void revoke(Credential credential) {
            byClientId.get(credential.clientId()).revoke(OffsetDateTime.now(), ACTOR);
        }

        void deactivateCompany(UUID companyId) {
            inactiveCompanies.add(companyId);
        }

        List<IntegrationRequest> inbox() {
            return List.copyOf(inbox);
        }

        int lookups() {
            return lookups;
        }

        private static void assignId(IntegrationClient client, UUID id) {
            try {
                var field = IntegrationClient.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(client, id);
            } catch (ReflectiveOperationException impossible) {
                throw new IllegalStateException("IntegrationClient.id could not be assigned", impossible);
            }
        }

        IntegrationClientRepository clientRepository() {
            return newProxy(IntegrationClientRepository.class, (proxy, method, args) -> {
                if ("findByClientId".equals(method.getName())) {
                    lookups++;
                    return Optional.ofNullable(byClientId.get((String) args[0]));
                }
                return unsupported(method.getName());
            });
        }

        IntegrationRequestRepository requestRepository() {
            return newProxy(IntegrationRequestRepository.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "saveAndFlush" -> {
                        inbox.add((IntegrationRequest) args[0]);
                        return args[0];
                    }
                    case "findByCompanyIdAndIntegrationClientIdAndOperationAndIdempotencyKey" -> {
                        UUID companyId = (UUID) args[0];
                        UUID clientId = (UUID) args[1];
                        String operation = (String) args[2];
                        String key = (String) args[3];
                        return inbox.stream()
                                .filter(row -> companyId.equals(row.companyId())
                                        && clientId.equals(row.integrationClientId())
                                        && operation.equals(row.operation())
                                        && key.equals(row.idempotencyKey()))
                                .findFirst();
                    }
                    default -> {
                        return unsupported(method.getName());
                    }
                }
            });
        }

        CompanyScopeLoader companyScopeLoader() {
            return companyId -> inactiveCompanies.contains(companyId)
                    ? Optional.empty()
                    : Optional.of(scopeOf(companyId));
        }

        private static CompanyScope scopeOf(UUID companyId) {
            boolean isA = companyId.equals(COMPANY_A);
            return new CompanyScope(companyId, isA ? "CO-A" : "CO-B", isA ? "Company A" : "Company B",
                    "America/Lima", ORGANIZATION, "ORG", "Org", java.util.Set.of());
        }

        @SuppressWarnings("unchecked")
        private static <T> T newProxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> {
                        // equals/hashCode/toString reach the proxy during bean post-processing.
                        switch (method.getName()) {
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "toString" -> {
                                return type.getSimpleName() + "-stub";
                            }
                            default -> {
                                return handler.invoke(proxy, method, args);
                            }
                        }
                    });
        }

        private static Object unsupported(String method) {
            throw new UnsupportedOperationException(
                    method + " is not exercised by this test; add it to the stub if it becomes relevant");
        }
    }

    /** Records the {@link CompanyScope} every location write was given, and answers CREATED. */
    static final class RecordingLocationIntake implements LocationIntakePort {

        private final List<UUID> scopes = new ArrayList<>();

        @Override
        public LocationIntakeResult upsert(CompanyScope scope, LocationIntakeCommand command) {
            scopes.add(scope.companyId());
            return new LocationIntakeResult(UUID.randomUUID(), command.code(), IntakeOutcome.CREATED);
        }

        List<UUID> scopes() {
            return List.copyOf(scopes);
        }

        void reset() {
            scopes.clear();
        }
    }

    /** The order counterpart. The fixed order number is what a replayed response must repeat. */
    static final class RecordingOrderIntake implements OrderIntakePort {

        private final List<UUID> scopes = new ArrayList<>();

        @Override
        public OrderIntakeResult upsert(CompanyScope scope, OrderIntakeCommand command) {
            scopes.add(scope.companyId());
            return new OrderIntakeResult(UUID.randomUUID(), "ORD-2026-000512", "DRAFT", IntakeOutcome.CREATED);
        }

        List<UUID> scopes() {
            return List.copyOf(scopes);
        }

        void reset() {
            scopes.clear();
        }
    }
}
