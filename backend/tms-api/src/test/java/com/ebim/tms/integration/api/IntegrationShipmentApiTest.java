package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.integration.application.IntegrationAuthenticationService;
import com.ebim.tms.integration.application.IntegrationShipmentService;
import com.ebim.tms.integration.domain.IntegrationClient;
import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.integration.infrastructure.IntegrationClientRepository;
import com.ebim.tms.integration.security.IntegrationSecurityConfig;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.reference.PublishedShipment;
import com.ebim.tms.shared.reference.PublishedShipmentDetail;
import com.ebim.tms.shared.reference.PublishedShipmentEvent;
import com.ebim.tms.shared.reference.ShipmentEventQuery;
import com.ebim.tms.shared.reference.ShipmentPublicationPort;
import com.ebim.tms.shared.reference.ShipmentPublicationQuery;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.web.WebConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * The outbound shipment API (job 08) exercised over real HTTP, through the real security chain -
 * the read-only counterpart of {@link IntegrationApiTenancyTest}.
 *
 * <p>The seam is {@link ShipmentPublicationPort}: a fake records the {@link CompanyScope} every
 * call was given instead of touching a database, which is what makes the tenancy assertions
 * possible without Docker, exactly as {@code IntegrationApiTenancyTest} does for the inbound side
 * at {@code LocationIntakePort}/{@code OrderIntakePort}.
 */
@WebMvcTest(
        controllers = IntegrationShipmentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = TmsJwtAuthenticationConverter.class))
@Import({
    ApplicationConfig.class,
    WebConfig.class,
    IntegrationSecurityConfig.class,
    IntegrationWebConfig.class,
    IntegrationAuthenticationService.class,
    IntegrationShipmentService.class,
    ApiExceptionResponder.class,
    ApiExceptionHandler.class,
    TmsAuthenticationEntryPoint.class,
    TmsAccessDeniedHandler.class,
    IntegrationShipmentApiTest.Fakes.class
})
@ActiveProfiles("test")
class IntegrationShipmentApiTest {

    private static final String SHIPMENTS = "/integration/v1/shipments";

    private static final UUID ORGANIZATION = UUID.fromString("88888888-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("88888888-0000-4000-8000-0000000000a1");
    private static final UUID COMPANY_B = UUID.fromString("88888888-0000-4000-8000-0000000000b1");
    private static final UUID ACTOR = UUID.fromString("88888888-0000-4000-8000-0000000000e1");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubClients clients;

    @Autowired
    private FakeShipmentPublication publication;

    private Credential readerOfA;

    @BeforeEach
    void issueCredentials() {
        clients.reset();
        publication.reset();
        readerOfA = clients.issue(COMPANY_A, IntegrationScope.SHIPMENT_READ);
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("a list read by company A's credential is scoped to company A")
        void listIsScopedToTheCredentialsCompany() throws Exception {
            mockMvc.perform(authenticated(get(SHIPMENTS), readerOfA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());

            assertThat(publication.searchCompanyIds()).containsExactly(COMPANY_A);
        }

        @Test
        @DisplayName("company B's credential never reads company A's shipments")
        void anotherCompanysCredentialCannotReadCompanyA() throws Exception {
            Credential readerOfB = clients.issue(COMPANY_B, IntegrationScope.SHIPMENT_READ);

            mockMvc.perform(authenticated(get(SHIPMENTS), readerOfB)).andExpect(status().isOk());

            assertThat(publication.searchCompanyIds()).containsExactly(COMPANY_B);
        }

        @Test
        @DisplayName("a shipment number that exists only in company B is 404 for company A")
        void detailNeverCrossesCompanies() throws Exception {
            publication.seedDetail(COMPANY_B, "SH-00000099");

            mockMvc.perform(authenticated(get(SHIPMENTS + "/SH-00000099"), readerOfA))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }
    }

    @Nested
    @DisplayName("scopes")
    class Scopes {

        @Test
        @DisplayName("a credential without integration.shipment:read is refused")
        void scopeIsRequired() throws Exception {
            Credential writerOnly = clients.issue(COMPANY_A, IntegrationScope.ORDER_WRITE);

            mockMvc.perform(authenticated(get(SHIPMENTS), writerOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("a request with no credential is refused, not served anonymously")
        void missingCredentialIsRefused() throws Exception {
            mockMvc.perform(get(SHIPMENTS)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        @DisplayName("status defaults to CONFIRMED only")
        void statusDefaultsToConfirmed() throws Exception {
            mockMvc.perform(authenticated(get(SHIPMENTS), readerOfA)).andExpect(status().isOk());

            assertThat(publication.lastQuery().statuses()).containsExactly("CONFIRMED");
        }

        @Test
        @DisplayName("an unrecognised status is refused with 400, not silently ignored")
        void invalidStatusIsRejected() throws Exception {
            mockMvc.perform(authenticated(get(SHIPMENTS + "?status=DRAFT"), readerOfA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("updatedSince reaches the port unchanged")
        void updatedSinceIsForwarded() throws Exception {
            mockMvc.perform(authenticated(get(SHIPMENTS + "?updatedSince=2026-08-19T00:00:00Z"), readerOfA))
                    .andExpect(status().isOk());

            assertThat(publication.lastQuery().updatedSince())
                    .isEqualTo(OffsetDateTime.parse("2026-08-19T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("detail")
    class Detail {

        @Test
        @DisplayName("a confirmed shipment's detail carries its stops and orders")
        void detailIsReturned() throws Exception {
            publication.seedDetail(COMPANY_A, "SH-00000042");

            mockMvc.perform(authenticated(get(SHIPMENTS + "/SH-00000042"), readerOfA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shipment.shipmentNumber").value("SH-00000042"))
                    .andExpect(jsonPath("$.shipment.companyCode").value("CO-A"))
                    .andExpect(jsonPath("$.stops[0].locationCode").value("ST-1"))
                    .andExpect(jsonPath("$.orders[0].externalReference").value("SO-1"));
        }

        @Test
        @DisplayName("an unknown shipment number is 404, indistinguishable from a draft trip")
        void unknownShipmentIs404() throws Exception {
            mockMvc.perform(authenticated(get(SHIPMENTS + "/SH-00000000"), readerOfA))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }
    }

    @Nested
    @DisplayName("change feed")
    class ChangeFeed {

        @Test
        @DisplayName("events are scoped to the credential's company and forward the watermark")
        void eventsAreScopedAndWatermarked() throws Exception {
            publication.seedEvent(COMPANY_A, "SH-00000042");

            mockMvc.perform(authenticated(get(SHIPMENTS + "/events?since=2026-08-19T00:00:00Z"), readerOfA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].eventType").value("SHIPMENT_CONFIRMED"))
                    .andExpect(jsonPath("$.content[0].shipmentNumber").value("SH-00000042"));

            assertThat(publication.lastEventQuery().companyId()).isEqualTo(COMPANY_A);
            assertThat(publication.lastEventQuery().since()).isEqualTo(OffsetDateTime.parse("2026-08-19T00:00:00Z"));
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, Credential credential) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential.bearerToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private record Credential(String clientId, String secret) {
        String bearerToken() {
            return IntegrationSecrets.toBearerToken(clientId, secret);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
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
        CompanyScopeLoader companyScopeLoader(StubClients clients) {
            return clients.companyScopeLoader();
        }

        @Bean
        FakeShipmentPublication fakeShipmentPublication() {
            return new FakeShipmentPublication();
        }
    }

    /** Trimmed down from {@link IntegrationApiTenancyTest.StubClients}: no inbox, no idempotency store. */
    static final class StubClients {

        private final Map<String, IntegrationClient> byClientId = new ConcurrentHashMap<>();
        private final Map<UUID, String> secretsByClient = new ConcurrentHashMap<>();

        void reset() {
            byClientId.clear();
            secretsByClient.clear();
        }

        Credential issue(UUID companyId, IntegrationScope... scopes) {
            String secret = IntegrationSecrets.newSecret();
            IntegrationClient client = new IntegrationClient(companyId, IntegrationSecrets.newClientId(),
                    "Reader of " + companyId, null, IntegrationSecrets.hash(secret), ACTOR);
            client.replaceScopes(List.of(scopes), ACTOR);
            assignId(client, UUID.randomUUID());
            byClientId.put(client.clientId(), client);
            secretsByClient.put(client.id(), secret);
            return new Credential(client.clientId(), secret);
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
                    return Optional.ofNullable(byClientId.get((String) args[0]));
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        CompanyScopeLoader companyScopeLoader() {
            return companyId -> Optional.of(scopeOf(companyId));
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
    }

    /** Records what {@link ShipmentPublicationPort} was asked, and answers from a tiny in-memory seed. */
    static final class FakeShipmentPublication implements ShipmentPublicationPort {

        private final List<UUID> searchCompanyIds = new ArrayList<>();
        private final Map<String, PublishedShipmentDetail> detailsByKey = new ConcurrentHashMap<>();
        private final Map<UUID, List<PublishedShipmentEvent>> eventsByCompany = new ConcurrentHashMap<>();
        private ShipmentPublicationQuery lastQuery;
        private ShipmentEventQuery lastEventQuery;

        void reset() {
            searchCompanyIds.clear();
            detailsByKey.clear();
            eventsByCompany.clear();
            lastQuery = null;
            lastEventQuery = null;
        }

        List<UUID> searchCompanyIds() {
            return List.copyOf(searchCompanyIds);
        }

        ShipmentPublicationQuery lastQuery() {
            return lastQuery;
        }

        ShipmentEventQuery lastEventQuery() {
            return lastEventQuery;
        }

        void seedDetail(UUID companyId, String shipmentNumber) {
            PublishedShipment header = new PublishedShipment(UUID.randomUUID(), companyId, shipmentNumber,
                    "PL-00000001", LocalDate.of(2026, 8, 20), "CONFIRMED", "CD-LIMA", "Distribution Center Lima",
                    null, null, null, "CR-1", "Carrier One", "VH-1", "ABC-123", "TRUCK-8T", "SNAPSHOT",
                    BigDecimal.valueOf(8000), BigDecimal.valueOf(32), BigDecimal.valueOf(18), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(1), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1, 1L, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now());
            var stop = new com.ebim.tms.shared.reference.PublishedShipmentStop(
                    UUID.randomUUID(), 1, "ST-1", "Store One", null, null, null, null);
            var order = new com.ebim.tms.shared.reference.PublishedShipmentOrder(
                    UUID.randomUUID(), "ORD-1", "ACME-ERP", "SO-1", "ST-1", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE);
            detailsByKey.put(companyId + "/" + shipmentNumber,
                    new PublishedShipmentDetail(header, List.of(stop), List.of(order)));
        }

        void seedEvent(UUID companyId, String shipmentNumber) {
            eventsByCompany.computeIfAbsent(companyId, key -> new ArrayList<>())
                    .add(new PublishedShipmentEvent(UUID.randomUUID(), "SHIPMENT_CONFIRMED", shipmentNumber, OffsetDateTime.now()));
        }

        @Override
        public PageResponse<PublishedShipment> search(ShipmentPublicationQuery query, PageQuery pageQuery) {
            searchCompanyIds.add(query.companyId());
            lastQuery = query;
            return new PageResponse<>(List.of(), pageQuery.pageNumber(), pageQuery.pageSize(), 0);
        }

        @Override
        public Optional<PublishedShipmentDetail> findDetail(UUID companyId, String shipmentNumber) {
            return Optional.ofNullable(detailsByKey.get(companyId + "/" + shipmentNumber));
        }

        @Override
        public PageResponse<PublishedShipmentEvent> searchEvents(ShipmentEventQuery query, PageQuery pageQuery) {
            lastEventQuery = query;
            List<PublishedShipmentEvent> content = eventsByCompany.getOrDefault(query.companyId(), List.of());
            return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), content.size());
        }
    }
}
