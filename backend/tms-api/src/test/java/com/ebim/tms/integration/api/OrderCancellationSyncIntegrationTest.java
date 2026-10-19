package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ADR-025 section 4 and {@code WAREHOUSE_EXECUTION_V1.md} 7.4-7.5 (V61): an order cancelled in TMS is
 * published as {@code ORDER_CANCELLED} and read back by the warehouse; an order the warehouse cancels
 * or closes short becomes a hold in TMS. Neither side cancels anything by itself (D-03).
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderCancellationSyncIntegrationTest.JwtDecoderOverride.class)
class OrderCancellationSyncIntegrationTest {

    private static final String EVENTS = "/integration/v1/warehouse-order-events";
    private static final String ORDERS = "/integration/v1/orders";
    private static final String PLANNING = "/api/v1/planning";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID OTHER_COMPANY = UUID.fromString("77777777-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destination;
    private static String carrier;
    private static String warehouseToken;
    private static String warehouseClientRowId;
    private static String otherCompanyToken;
    private static String milestoneOnlyToken;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_cancellation_sync");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        // Webhooks on, so ORDER_CANCELLED can be seen reaching a subscription. The target is never
        // called: the dispatcher's first pass is an hour away.
        registry.add("tms.integration.webhooks.secret-key", () -> "ocx-test-webhook-key-0123456789abcdef");
        registry.add("tms.integration.webhooks.allow-insecure-targets", () -> "true");
        registry.add("tms.integration.webhooks.allow-private-network-targets", () -> "true");
        registry.add("tms.integration.webhooks.poll-interval", () -> "1h");
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'OCX-ORG', 'Cancellation Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'OCX-A', 'Cancellation Company', 'America/Lima'),
                    ('%s', '%s', 'OCX-B', 'Other Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'ocx.admin@example.invalid', 'OCX Admin');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'ocx.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'ocx.admin@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, OTHER_COMPANY, ORGANIZATION, ADMIN_AUTH,
                ORGANIZATION, COMPANY, COMPANY));
        origin = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'CD-OCX', 'CD OCX')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + origin + "', 'ORIGIN')");
        destination = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'CLI-OCX', 'Cliente OCX')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + destination + "', 'DESTINATION')");
        carrier = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY + "', 'OCXT', 'Transportes OCX', 'RUC', '20100000019')");

        String[] warehouse = client(COMPANY, "EWM warehouse", "integration.warehouse-order-event:write",
                "integration.order:read");
        warehouseToken = warehouse[0];
        warehouseClientRowId = warehouse[1];
        otherCompanyToken = client(OTHER_COMPANY, "Other EWM", "integration.warehouse-order-event:write",
                "integration.order:read")[0];
        milestoneOnlyToken = client(COMPANY, "Milestones only", "integration.warehouse-milestone:write")[0];
    }

    @BeforeEach
    void mintToken() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
    }

    // --- warehouse -> TMS ----------------------------------------------------------------------

    @Test
    @DisplayName("ORDER_CANCELLED on an open order places a blocking INTEGRATION hold and changes no status; a repeat is DUPLICATE")
    void cancelledPlacesABlockingHold() throws Exception {
        Order order = order("READY_FOR_PLANNING");
        String eventId = UUID.randomUUID().toString();

        events(warehouseToken, null, batch(cancelled(eventId, order.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.recorded").value(1))
                .andExpect(jsonPath("$.refused").value(0))
                .andExpect(jsonPath("$.results[0].result").value("HOLD_PLACED"))
                .andExpect(jsonPath("$.results[0].orderNumber").value(order.number));

        assertThat(value("SELECT status FROM tms.transport_order WHERE id = '" + order.id + "'"))
                .isEqualTo("READY_FOR_PLANNING");
        assertThat(value("SELECT hold_type || ':' || reason_code || ':' || source || ':' || blocking || ':'"
                + " || created_by_client FROM tms.order_hold WHERE order_id = '" + order.id + "'"))
                .isEqualTo("INTEGRATION:WAREHOUSE_CANCELLED:INTEGRATION:true:" + warehouseClientRowId);
        assertThat(value("SELECT reason FROM tms.order_hold WHERE order_id = '" + order.id + "'"))
                .contains("ORR-001").contains("CD01").contains("Anulado por el ERP");
        assertThat(value("SELECT outcome || ':' || (hold_id IS NOT NULL) FROM tms.warehouse_order_event"
                + " WHERE event_id = '" + eventId + "'")).isEqualTo("HOLD_PLACED:true");
        assertThat(value("SELECT count(*) FROM tms.audit_event WHERE action = 'ORDER_HOLD_PLACED' AND aggregate_id = '"
                + order.id + "' AND metadata LIKE '%INTEGRATION%'")).isEqualTo("1");
        mockMvc.perform(asAdmin(get("/api/v1/orders/" + order.id + "/holds"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("INTEGRATION"));

        events(warehouseToken, null, batch(cancelled(eventId, order.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(0))
                .andExpect(jsonPath("$.results[0].result").value("DUPLICATE"));
        assertThat(value("SELECT count(*) FROM tms.order_hold WHERE order_id = '" + order.id + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("a second cancellation of the same order is ALREADY_HELD; a short close is a non-blocking NOTED hold with the figures")
    void alreadyHeldAndShortClosed() throws Exception {
        Order order = order("READY_FOR_PLANNING");
        events(warehouseToken, null, batch(cancelled(UUID.randomUUID().toString(), order.reference)))
                .andExpect(jsonPath("$.results[0].result").value("HOLD_PLACED"));
        events(warehouseToken, null, batch(cancelled(UUID.randomUUID().toString(), order.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(1))
                .andExpect(jsonPath("$.results[0].result").value("ALREADY_HELD"));

        Order shortOrder = order("NOT_READY");
        events(warehouseToken, null, batch(shortClosed(UUID.randomUUID().toString(), shortOrder.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("NOTED"));
        assertThat(value("SELECT reason_code || ':' || blocking FROM tms.order_hold WHERE order_id = '"
                + shortOrder.id + "'")).isEqualTo("WAREHOUSE_SHORT_CLOSED:false");
        assertThat(value("SELECT reason FROM tms.order_hold WHERE order_id = '" + shortOrder.id + "'"))
                .contains("line 1 SKU-100: requested 100, shipped 97, cancelled 3 UN");
        assertThat(value("SELECT status FROM tms.transport_order WHERE id = '" + shortOrder.id + "'"))
                .isEqualTo("NOT_READY");
    }

    @Test
    @DisplayName("an unknown key is UNKNOWN_ORDER, a cancelled order is ORDER_CLOSED; both are recorded, nothing is held")
    void unknownAndClosed() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        Order closed = order("CANCELLED");
        events(warehouseToken, null, batch(cancelled(unknownId, "PED-NOPE-" + SEQUENCE.incrementAndGet()) + ","
                        + cancelled(UUID.randomUUID().toString(), closed.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(2))
                .andExpect(jsonPath("$.results[0].result").value("UNKNOWN_ORDER"))
                .andExpect(jsonPath("$.results[0].orderNumber").value(nullValue()))
                .andExpect(jsonPath("$.results[1].result").value("ORDER_CLOSED"))
                .andExpect(jsonPath("$.results[1].orderNumber").value(closed.number));
        assertThat(value("SELECT (order_id IS NULL) || ':' || outcome FROM tms.warehouse_order_event WHERE event_id = '"
                + unknownId + "'")).isEqualTo("true:UNKNOWN_ORDER");
        assertThat(value("SELECT count(*) FROM tms.order_hold WHERE order_id = '" + closed.id + "'")).isEqualTo("0");
    }

    @Test
    @DisplayName("a malformed item is INVALID with a message (207) and its neighbour still lands")
    void invalidItemIs207() throws Exception {
        Order order = order("READY_FOR_PLANNING");
        events(warehouseToken, null, batch(cancelled(UUID.randomUUID().toString(), order.reference) + ","
                        + "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"ORDER_DELETED\",\"externalSource\":\"SAPB1_PE\","
                        + "\"externalReference\":\"PED-X\",\"occurredAt\":\"2026-10-17T08:55:00-05:00\"}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.refused").value(1))
                .andExpect(jsonPath("$.results[0].result").value("HOLD_PLACED"))
                .andExpect(jsonPath("$.results[1].result").value("INVALID"))
                .andExpect(jsonPath("$.results[1].message").value(containsString("type")));
    }

    @Test
    @DisplayName("the same Idempotency-Key replays the first answer; with another body it is 409")
    void idempotencyKey() throws Exception {
        Order order = order("READY_FOR_PLANNING");
        String key = "ewm-ordcx:" + UUID.randomUUID();
        String body = batch(cancelled(UUID.randomUUID().toString(), order.reference));
        events(warehouseToken, key, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("HOLD_PLACED"));
        events(warehouseToken, key, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("HOLD_PLACED"));
        events(warehouseToken, key, batch(cancelled(UUID.randomUUID().toString(), order.reference)))
                .andExpect(status().isConflict());
        assertThat(value("SELECT count(*) FROM tms.order_hold WHERE order_id = '" + order.id + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("another company's credential cannot reach the order (UNKNOWN_ORDER); a credential without the scope is 403")
    void tenancyAndScope() throws Exception {
        Order order = order("READY_FOR_PLANNING");
        events(otherCompanyToken, null, batch(cancelled(UUID.randomUUID().toString(), order.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("UNKNOWN_ORDER"));
        events(milestoneOnlyToken, null, batch(cancelled(UUID.randomUUID().toString(), order.reference)))
                .andExpect(status().isForbidden());
        assertThat(value("SELECT count(*) FROM tms.order_hold WHERE order_id = '" + order.id + "'")).isEqualTo("0");
    }

    @Test
    @DisplayName("on a committed trip the hold unplans nothing: dispatch is refused and ORDER_HOLD_ON_COMMITTED_TRIP is raised")
    void committedTripIsBlocked() throws Exception {
        LocalDate date = nextDate();
        Order order = releasedOrder(date);
        String run = newRun(date);
        String trip = newTrip(run, date);
        mockMvc.perform(asAdmin(post(PLANNING + "/trips/" + trip + "/assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order.id + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/confirm")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        transition(trip, "ready").andExpect(status().isOk());

        events(warehouseToken, null, batch(cancelled(UUID.randomUUID().toString(), order.reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("HOLD_PLACED"));

        assertThat(value("SELECT status FROM tms.transport_order WHERE id = '" + order.id + "'")).isEqualTo("PLANNED");
        assertThat(value("SELECT count(*) FROM tms.audit_event WHERE action = 'ORDER_HOLD_PLACED' AND aggregate_id = '"
                + order.id + "' AND metadata LIKE '%onCommittedTrip%'")).isEqualTo("1");
        transition(trip, "dispatch")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("active blocking hold")));
        mockMvc.perform(asAdmin(get("/api/v1/monitoring/control-tower")).param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories[?(@.type=='ORDER_HOLD_ON_COMMITTED_TRIP')].sourceId")
                        .value(hasItem(order.id)));
    }

    // --- TMS -> warehouse ----------------------------------------------------------------------

    @Test
    @DisplayName("cancelling an order writes ORDER_CANCELLED to the outbox and the subscribed webhook; the warehouse reads it back")
    void cancellationIsPublishedAndReadable() throws Exception {
        String subscription = subscribe("ORDER_CANCELLED");
        Order order = order("READY_FOR_PLANNING");
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);

        mockMvc.perform(asAdmin(post("/api/v1/orders/" + order.id + "/cancel")).param("reason", "Cliente anuló la compra"))
                .andExpect(status().isOk());

        String eventId = value("SELECT id FROM tms.business_outbox_event WHERE resource_id = '" + order.id
                + "' AND event_type = 'ORDER_CANCELLED'");
        assertThat(value("SELECT resource_type || ':' || resource_reference FROM tms.business_outbox_event WHERE id = '"
                + eventId + "'")).isEqualTo("ORDER:" + order.number);
        String payload = value("SELECT payload FROM tms.webhook_delivery WHERE webhook_subscription_id = '"
                + subscription + "' AND event_id = '" + eventId + "'");
        assertThat(payload).contains("\"type\":\"ORDER_CANCELLED\"").contains("\"type\":\"order\"")
                .contains("\"reference\":\"" + order.number + "\"").contains("\"id\":\"" + eventId + "\"");

        readOrder(warehouseToken, order.number)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(order.number))
                .andExpect(jsonPath("$.externalSource").value("SAPB1_PE"))
                .andExpect(jsonPath("$.externalReference").value(order.reference))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Cliente anuló la compra"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        mockMvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .param("status", "CANCELLED").param("updatedSince", before.toString()).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orderNumber").value(hasItem(order.number)));
    }

    @Test
    @DisplayName("an open order reads with no cancellation; another company's is 404; an unknown status is 400; no scope is 403")
    void orderReadRules() throws Exception {
        Order order = order("READY_FOR_PLANNING");
        readOrder(warehouseToken, order.number)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"))
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()));
        readOrder(otherCompanyToken, order.number).andExpect(status().isNotFound());
        readOrder(milestoneOnlyToken, order.number).andExpect(status().isForbidden());
        mockMvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken).param("status", "GONE"))
                .andExpect(status().isBadRequest());
        // The default feed is the cancelled one: an open order is not in it.
        mockMvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.orderNumber=='" + order.number + "')]").isEmpty());
    }

    // --- helpers -------------------------------------------------------------------------------

    private record Order(String id, String number, String reference) {
    }

    private ResultActions events(String token, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(EVENTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private ResultActions readOrder(String token, String number) throws Exception {
        return mockMvc.perform(get(ORDERS + "/" + number).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String batch(String events) {
        return "{\"sourceSystem\":\"EWM_EBIM\",\"events\":[" + events + "]}";
    }

    private static String cancelled(String eventId, String reference) {
        return """
                {"eventId":"%s","type":"ORDER_CANCELLED","externalSource":"SAPB1_PE","externalReference":"%s",
                 "warehouseOrderNumber":"ORR-001-00000009","warehouseCode":"CD01",
                 "occurredAt":"2026-10-17T08:55:00-05:00","reason":"Anulado por el ERP (ORR DELETE)"}
                """.formatted(eventId, reference);
    }

    private static String shortClosed(String eventId, String reference) {
        return """
                {"eventId":"%s","type":"ORDER_SHORT_CLOSED","externalSource":"SAPB1_PE","externalReference":"%s",
                 "warehouseOrderNumber":"ORR-001-00000007","warehouseCode":"CD01",
                 "occurredAt":"2026-10-17T09:30:00-05:00","reason":"Saldo anulado tras despacho parcial",
                 "lines":[{"lineNumber":1,"materialCode":"SKU-100","uom":"UN","requested":100,"shipped":97,"cancelled":3}]}
                """.formatted(eventId, reference);
    }

    private String subscribe(String eventType) throws Exception {
        String response = mockMvc.perform(asAdmin(post("/api/v1/webhooks")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"WMS " + SEQUENCE.incrementAndGet() + "\","
                                + "\"targetUrl\":\"http://wms.example.invalid/tms\",\"eventTypes\":[\"" + eventType + "\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.subscription.id");
    }

    private ResultActions transition(String tripId, String action) throws Exception {
        String body = mockMvc.perform(asAdmin(get(PLANNING + "/trips/" + tripId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(body, "$.trip.version");
        return mockMvc.perform(asAdmin(post(PLANNING + "/trips/" + tripId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version.longValue() + "}"));
    }

    private String newRun(LocalDate date) throws Exception {
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.run.id");
    }

    private String newTrip(String runId, LocalDate date) throws Exception {
        String departure = date.atTime(8, 0).atZone(LIMA).toOffsetDateTime().toString();
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + runId + "/trips"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle() + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.trip.id");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken).header(ApiHeaders.COMPANY_ID, COMPANY.toString());
    }

    private static LocalDate nextDate() {
        return LocalDate.now(LIMA).minusDays(60).plusDays(SEQUENCE.incrementAndGet());
    }

    private static Order order(String status) {
        return order(nextDate(), status);
    }

    private static Order releasedOrder(LocalDate serviceDate) {
        return order(serviceDate, "READY_FOR_PLANNING");
    }

    /** An order of one line of 100 UN, keyed SAPB1_PE, in {@code status}. */
    private static Order order(LocalDate serviceDate, String status) {
        int number = SEQUENCE.incrementAndGet();
        String orderNumber = "TO-OCX-" + String.format(Locale.ROOT, "%06d", number);
        String reference = "PED-OCX-" + number;
        String id = insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets,"
                + " external_source, external_reference) VALUES ('" + COMPANY + "', '" + orderNumber + "', '" + origin
                + "', '" + destination + "', '" + serviceDate + "', '" + status + "', 1000, 10, 2, 'SAPB1_PE', '"
                + reference + "')");
        execute("INSERT INTO tms.transport_order_line (order_id, line_number, material_code, material_description,"
                + " quantity, uom) VALUES ('" + id + "', 1, 'SKU-100', 'Cemento', 100, 'UN')");
        return new Order(id, orderNumber, reference);
    }

    private static String vehicle() {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'OCX-TYPE-" + number
                + "', 'Type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'OCX-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "O%02d-%03d", number % 100, number) + "', '" + carrier + "', '" + type
                + "')");
    }

    private static String[] client(UUID company, String name, String... scopes) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String rowId = insertReturningId("INSERT INTO tms.integration_client (company_id, client_id, name,"
                + " secret_hash) VALUES ('" + company + "', '" + clientId + "', '" + name + "', '"
                + IntegrationSecrets.hash(secret) + "')");
        for (String scope : scopes) {
            execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + rowId
                    + "', '" + scope + "')");
        }
        return new String[] {IntegrationSecrets.toBearerToken(clientId, secret), rowId};
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
