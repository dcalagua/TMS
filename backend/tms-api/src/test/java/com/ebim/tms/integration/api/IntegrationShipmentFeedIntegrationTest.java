package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The two shipment feeds a warehouse polls - {@code GET /integration/v1/shipments} and
 * {@code GET /integration/v1/shipments/events} - read over real HTTP against a real PostgreSQL.
 *
 * <p>Found in QAS on 2026-10-22: both answered 500 ({@code could not determine data type of
 * parameter $3}) with or without the lower bound, because the queries said
 * {@code (:since IS NULL OR column >= :since)} and PostgreSQL cannot type a bind parameter that
 * appears only in an {@code IS NULL} test. Every other test of these endpoints used a fake port,
 * so nothing ran the real query. This one does, with the exact request the EWM sends.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(IntegrationShipmentFeedIntegrationTest.JwtDecoderOverride.class)
class IntegrationShipmentFeedIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String SHIPMENTS = "/integration/v1/shipments";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c1");
    private static final UUID ADMIN_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e1");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destination;
    private static String carrier;
    private static String readerToken;

    @Autowired
    private MockMvc mockMvc;

    /** The shipment numbers this class confirmed, in creation order. */
    private final List<String> created = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtDecoderOverride {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_integration_shipment_feed");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'FEED-ORG', 'Feed Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'FEED-A', 'Feed Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'feed.admin@example.invalid', 'Feed Admin');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'feed.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'feed.admin@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, ADMIN_AUTH, ORGANIZATION, COMPANY, COMPANY));
        origin = insertReturningId("INSERT INTO tms.location (company_id, code, name, external_system,"
                + " external_reference) VALUES ('" + COMPANY + "', 'CD-FEED', 'CD Feed', 'EWM', 'CD01')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + origin + "', 'ORIGIN')");
        destination = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'TIENDA-1', 'Tienda 1')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + destination + "', 'DESTINATION')");
        carrier = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY + "', 'TRFD', 'Transportes Feed', 'RUC', '20100000019')");
        readerToken = client("EWM warehouse", "integration.shipment:read");
    }

    @BeforeAll
    void confirmThreeShipments() throws Exception {
        for (int i = 0; i < 3; i++) {
            created.add(confirmedShipment());
        }
    }

    @Test
    @DisplayName("GET /shipments without updatedSince answers 200 with every confirmed shipment, oldest-touched first")
    void listWithoutLowerBound() throws Exception {
        String body = feed(get(SHIPMENTS));

        List<String> numbers = JsonPath.read(body, "$.content[*].shipmentNumber");
        assertThat(numbers).containsExactlyElementsOf(created);
        assertThat(((Number) JsonPath.read(body, "$.totalElements")).intValue()).isEqualTo(3);
        assertNonDecreasing(JsonPath.read(body, "$.content[*].updatedAt"));
    }

    @Test
    @DisplayName("GET /shipments?sort=updatedAt,asc&size=200&page=0 - the EWM's own request - answers 200")
    void listAsTheWarehouseAsksForIt() throws Exception {
        String body = feed(get(SHIPMENTS).param("sort", "updatedAt,asc").param("size", "200").param("page", "0"));

        List<String> numbers = JsonPath.read(body, "$.content[*].shipmentNumber");
        assertThat(numbers).containsExactlyElementsOf(created);
        assertThat(((Number) JsonPath.read(body, "$.size")).intValue()).isEqualTo(200);
        assertNonDecreasing(JsonPath.read(body, "$.content[*].updatedAt"));
    }

    @Test
    @DisplayName("GET /shipments?updatedSince=<second> answers 200 and leaves out what was touched before it")
    void listWithLowerBound() throws Exception {
        String since = value("SELECT to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
                + " FROM tms.trip WHERE shipment_number = '" + created.get(1) + "'");

        String body = feed(get(SHIPMENTS).param("updatedSince", since)
                .param("sort", "updatedAt,asc").param("size", "200").param("page", "0"));

        List<String> numbers = JsonPath.read(body, "$.content[*].shipmentNumber");
        assertThat(numbers).containsExactly(created.get(1), created.get(2));
    }

    @Test
    @DisplayName("GET /shipments?updatedSince=<future> answers 200 with an empty page")
    void listWithFutureLowerBound() throws Exception {
        String body = feed(get(SHIPMENTS).param("updatedSince", OffsetDateTime.now().plusDays(1).toString()));

        List<String> numbers = JsonPath.read(body, "$.content[*].shipmentNumber");
        assertThat(numbers).isEmpty();
    }

    @Test
    @DisplayName("GET /shipments/events without since answers 200, oldest first")
    void eventsWithoutLowerBound() throws Exception {
        String body = feed(get(SHIPMENTS + "/events").param("size", "200").param("page", "0"));

        List<String> numbers = JsonPath.read(body, "$.content[*].shipmentNumber");
        assertThat(numbers).containsAll(created);
        assertNonDecreasing(JsonPath.read(body, "$.content[*].occurredAt"));
    }

    @Test
    @DisplayName("GET /shipments/events?since=<future> answers 200 with an empty page; since=<past> with everything")
    void eventsWithLowerBound() throws Exception {
        String future = feed(get(SHIPMENTS + "/events").param("since", OffsetDateTime.now().plusDays(1).toString()));
        assertThat((List<Object>) JsonPath.read(future, "$.content")).isEmpty();

        String past = feed(get(SHIPMENTS + "/events").param("since", OffsetDateTime.now().minusDays(1).toString()));
        List<String> numbers = JsonPath.read(past, "$.content[*].shipmentNumber");
        assertThat(numbers).containsAll(created);
    }

    private String feed(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static void assertNonDecreasing(List<String> timestamps) {
        for (int i = 1; i < timestamps.size(); i++) {
            assertThat(OffsetDateTime.parse(timestamps.get(i)))
                    .as("element %d is not older than element %d", i, i - 1)
                    .isAfterOrEqualTo(OffsetDateTime.parse(timestamps.get(i - 1)));
        }
    }

    /** A run with one trip carrying one order, confirmed; returns the trip's shipment number. */
    private String confirmedShipment() throws Exception {
        LocalDate date = LocalDate.now(LIMA).minusDays(30).plusDays(SEQUENCE.incrementAndGet());
        String order = order(date);
        String run = JsonPath.read(mockMvc.perform(asAdmin(post(PLANNING + "/runs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.run.id");
        String departure = date.atTime(8, 0).atZone(LIMA).toOffsetDateTime().toString();
        String trip = JsonPath.read(mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/trips"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle() + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.trip.id");
        mockMvc.perform(asAdmin(post(PLANNING + "/trips/" + trip + "/assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        return value("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(ADMIN_AUTH))
                .header(ApiHeaders.COMPANY_ID, COMPANY.toString());
    }

    private static String client(String name, String... scopes) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String rowId = insertReturningId("INSERT INTO tms.integration_client (company_id, client_id, name,"
                + " secret_hash) VALUES ('" + COMPANY + "', '" + clientId + "', '" + name + "', '"
                + IntegrationSecrets.hash(secret) + "')");
        for (String scope : scopes) {
            execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + rowId
                    + "', '" + scope + "')");
        }
        return IntegrationSecrets.toBearerToken(clientId, secret);
    }

    private static String order(LocalDate serviceDate) {
        int number = SEQUENCE.incrementAndGet();
        String id = insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets,"
                + " external_source, external_reference) VALUES ('" + COMPANY + "', 'TO-FEED-"
                + String.format(Locale.ROOT, "%06d", number) + "', '" + origin + "', '" + destination + "', '"
                + serviceDate + "', 'READY_FOR_PLANNING', 1000, 10, 2, 'SAPB1_PE', 'PED-FEED-" + number + "')");
        execute("INSERT INTO tms.transport_order_line (order_id, line_number, material_code, material_description,"
                + " quantity, uom) VALUES ('" + id + "', 1, 'SKU-100', 'Cemento', 100, 'UN')");
        return id;
    }

    private static String vehicle() {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'FEED-TYPE-" + number
                + "', 'Type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'FEED-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "F%02d-%03d", number % 100, number) + "', '" + carrier + "', '" + type + "')");
    }

    private static String insertReturningId(String sql) {
        return value(sql + " RETURNING id");
    }

    private static String value(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run " + sql, failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run " + sql, failed);
        }
    }
}
