package com.ebim.tms.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * V70 end to end: a carrier user works the stops of their own dispatched trip from the portal -
 * arrival, delivery result with refused quantities and evidence, incident, completion - through the
 * office's own services. The facts land with the source {@code CARRIER_PORTAL}, the office sees who
 * recorded them, the warehouse webhook {@code DELIVERY_RESULT_RECORDED} is fanned out as for an
 * office recording, a replay with the same {@code Idempotency-Key} records nothing twice, and
 * another carrier can neither act on the trip nor - as the runtime role - read its execution rows.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CarrierStopExecutionIntegrationTest.JwtDecoderOverride.class)
class CarrierStopExecutionIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";
    private static final String PORTAL = "/api/v1/portal/trips";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("70707070-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("70707070-0000-4000-8000-0000000000c1");
    private static final UUID ADMIN_AUTH = UUID.fromString("70707070-0000-4000-8000-0000000000e1");
    private static final UUID CARRIER_A_AUTH = UUID.fromString("70707070-0000-4000-8000-0000000000e2");
    private static final UUID CARRIER_B_AUTH = UUID.fromString("70707070-0000-4000-8000-0000000000e3");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destination;
    private static String carrierA;
    private static String carrierB;
    private static String warehouseToken;

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtDecoderOverride {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_carrier_stop_execution");
        seed();
        String root = Files.createTempDirectory("tms-portal-pod-evidence").toAbsolutePath().toString();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("tms.storage.evidence.mode", () -> "LOCAL");
        registry.add("tms.storage.evidence.root", () -> root);
        // Webhooks on, so the test can prove DELIVERY_RESULT_RECORDED reaches the warehouse when the
        // carrier records it - the same fan-out as the office's.
        registry.add("tms.integration.webhooks.secret-key", () -> "pod-test-webhook-key-0123456789abcdef");
        registry.add("tms.integration.webhooks.allow-insecure-targets", () -> "true");
        registry.add("tms.integration.webhooks.allow-private-network-targets", () -> "true");
        registry.add("tms.integration.webhooks.poll-interval", () -> "1h");
    }

    private static void seed() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%1$s', 'POD-ORG', 'Portal POD Org');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%2$s', '%1$s', 'POD-A', 'Portal POD Company', 'America/Lima');
                INSERT INTO tms.company_settings (company_id, dispatch_confirmation_mode)
                    VALUES ('%2$s', 'EXTERNAL_REQUIRED');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%3$s', 'pod.admin@example.invalid', 'Office Admin');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%1$s', '%2$s' FROM tms.app_user WHERE email = 'pod.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'pod.admin@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN';
                """.formatted(ORGANIZATION, COMPANY, ADMIN_AUTH));
        origin = insertReturningId("INSERT INTO tms.location (company_id, code, name, external_system,"
                + " external_reference) VALUES ('" + COMPANY + "', 'CD-LIMA', 'CD Lima', 'EWM', 'CD01')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + origin + "', 'ORIGIN')");
        destination = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'CLIENTE-1', 'Cliente Uno')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + destination + "', 'DESTINATION')");
        carrierA = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY + "', 'TRSA', 'Transportes SA', 'RUC', '20100000019')");
        carrierB = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY + "', 'TRSB', 'Transportes SB', 'RUC', '20100000027')");
        carrierUser(CARRIER_A_AUTH, "chofer.pod.a@example.invalid", carrierA);
        carrierUser(CARRIER_B_AUTH, "chofer.pod.b@example.invalid", carrierB);
        warehouseToken = client("EWM", "integration.dispatch:write");
    }

    private static void carrierUser(UUID auth, String email, String carrier) {
        execute("""
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('%1$s', '%2$s', '%2$s');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id, carrier_id)
                SELECT id, '%3$s', '%4$s', '%5$s' FROM tms.app_user WHERE email = '%2$s';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%2$s'
                JOIN tms.role r ON r.code = 'CARRIER_OPERATOR';
                """.formatted(auth, email, ORGANIZATION, COMPANY, carrier));
    }

    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("the carrier works the stop end to end; facts carry CARRIER_PORTAL and the office sees who recorded them")
    void carrierWorksTheStop() throws Exception {
        String subscription = subscribe("DELIVERY_RESULT_RECORDED");
        Shipment shipment = dispatchedShipment(carrierA);

        String detail = asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.stops[0].executionStatus").value("PENDING"))
                .andExpect(jsonPath("$.stops[0].orders.length()").value(1))
                .andExpect(jsonPath("$.stops[0].orders[0].orderId").value(shipment.orderId))
                .andExpect(jsonPath("$.stops[0].orders[0].delivery").doesNotExist())
                .andExpect(jsonPath("$.incidents.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String stopId = JsonPath.read(detail, "$.stops[0].stopId");

        // Nothing can be delivered at a stop nobody has reached - the office's rule, unchanged.
        delivery(CARRIER_A_AUTH, shipment, stopId, UUID.randomUUID().toString(), "DELIVERED", null, false)
                .andExpect(status().isConflict());

        String arriveKey = UUID.randomUUID().toString();
        stopAction(CARRIER_A_AUTH, shipment.tripId, stopId, "arrive", arriveKey, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].executionStatus").value("ARRIVED"))
                .andExpect(jsonPath("$.stops[0].actualArrivalAt").isNotEmpty());
        // The offline queue resends it: same answer, nothing recorded twice.
        stopAction(CARRIER_A_AUTH, shipment.tripId, stopId, "arrive", arriveKey, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].executionStatus").value("ARRIVED"));
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'ARRIVED_AT_STOP'")).isEqualTo("1");
        assertThat(value("SELECT source FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'ARRIVED_AT_STOP'")).isEqualTo("CARRIER_PORTAL");
        // The same key on another action is a client bug, refused.
        stopAction(CARRIER_A_AUTH, shipment.tripId, stopId, "complete", arriveKey, "{}")
                .andExpect(status().isConflict());

        // A partial delivery: what was refused, the receiver, a photo and a signature, in one request.
        String deliveryKey = UUID.randomUUID().toString();
        delivery(CARRIER_A_AUTH, shipment, stopId, deliveryKey, "PARTIAL", "2", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.result").value("PARTIAL"))
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.receiverName").value("M. Quispe"))
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.source").value("CARRIER_PORTAL"))
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.evidenceCount").value(2))
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.refusedPallets").value(2));
        delivery(CARRIER_A_AUTH, shipment, stopId, deliveryKey, "PARTIAL", "2", true)
                .andExpect(status().isOk());
        assertThat(value("SELECT count(*) FROM tms.delivery_evidence e JOIN tms.order_delivery d"
                + " ON d.id = e.order_delivery_id WHERE d.trip_id = '" + shipment.tripId + "'")).isEqualTo("2");
        assertThat(value("SELECT count(*) FROM tms.shipment_outbox_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'DELIVERY_RESULT_RECORDED'")).isEqualTo("1");
        assertThat(value("SELECT delivered_pallets || '/' || refused_pallets || '/' || attempted_pallets"
                + " FROM tms.order_delivery WHERE trip_id = '" + shipment.tripId + "'")).isEqualTo("8.00/2.00/10.00");
        // The warehouse is told exactly as when the office records it.
        assertThat(value("SELECT count(*) FROM tms.webhook_delivery WHERE webhook_subscription_id = '" + subscription
                + "' AND event_type = 'DELIVERY_RESULT_RECORDED' AND payload LIKE '%\"" + shipment.shipmentNumber
                + "\"%'")).isEqualTo("1");

        String incidentKey = UUID.randomUUID().toString();
        asCarrier(CARRIER_A_AUTH, post(PORTAL + "/" + shipment.tripId + "/incidents")
                        .header(ApiHeaders.IDEMPOTENCY_KEY, incidentKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripStopId\":\"" + stopId + "\",\"exceptionType\":\"OTHER\","
                                + "\"notes\":\"Dos pallets con film roto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents.length()").value(1))
                .andExpect(jsonPath("$.incidents[0].exceptionType").value("OTHER"))
                .andExpect(jsonPath("$.incidents[0].status").value("OPEN"));

        stopAction(CARRIER_A_AUTH, shipment.tripId, stopId, "complete", UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].executionStatus").value("COMPLETED"));

        // The office's trip workspace: the result and the timeline say the carrier recorded them.
        asAdmin(get(TRIPS + "/" + shipment.tripId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[0].source").value("CARRIER_PORTAL"))
                .andExpect(jsonPath("$.deliveries[0].recordedByName").isNotEmpty())
                .andExpect(jsonPath("$.stops[0].executionStatus").value("COMPLETED"));
        asAdmin(get(TRIPS + "/" + shipment.tripId + "/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'ARRIVED_AT_STOP')].source").value("CARRIER_PORTAL"))
                .andExpect(jsonPath("$[?(@.eventType == 'STOP_COMPLETED')].source").value("CARRIER_PORTAL"))
                .andExpect(jsonPath("$[?(@.eventType == 'EXCEPTION_REPORTED')].source").value("CARRIER_PORTAL"));
        assertThat(value("SELECT count(*) FROM tms.portal_action_receipt WHERE trip_id = '" + shipment.tripId + "'"))
                .isEqualTo("4");

        // The office closes the trip out: from then on corrections are the office's.
        asAdmin(post(TRIPS + "/" + shipment.tripId + "/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + value("SELECT version FROM tms.trip WHERE id = '" + shipment.tripId
                                + "'") + "}"))
                .andExpect(status().isOk());
        delivery(CARRIER_A_AUTH, shipment, stopId, UUID.randomUUID().toString(), "DELIVERED", null, false)
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the office's rules refuse a bad portal delivery, and nothing is half-written")
    void deliveryRules() throws Exception {
        Shipment shipment = dispatchedShipment(carrierA);
        String stopId = stopIdOf(shipment);
        stopAction(CARRIER_A_AUTH, shipment.tripId, stopId, "arrive", UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk());

        // No key, no write.
        delivery(CARRIER_A_AUTH, shipment, stopId, null, "DELIVERED", null, false).andExpect(status().isBadRequest());
        // A partial delivery says what was refused, and cannot refuse more than was carried.
        delivery(CARRIER_A_AUTH, shipment, stopId, UUID.randomUUID().toString(), "PARTIAL", null, false)
                .andExpect(status().isBadRequest());
        delivery(CARRIER_A_AUTH, shipment, stopId, UUID.randomUUID().toString(), "PARTIAL", "11", false)
                .andExpect(status().isBadRequest());
        // A rejection explains itself (TripDeliveryService's rule).
        MockMultipartHttpServletRequestBuilder rejected =
                multipart(PORTAL + "/" + shipment.tripId + "/stops/" + stopId + "/orders/" + shipment.orderId
                        + "/delivery");
        rejected.param("result", "REJECTED");
        rejected.header(ApiHeaders.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
        asCarrier(CARRIER_A_AUTH, rejected).andExpect(status().isBadRequest());
        // A file of a type the store does not take refuses the whole request before anything is stored.
        MockMultipartHttpServletRequestBuilder badFile =
                multipart(PORTAL + "/" + shipment.tripId + "/stops/" + stopId + "/orders/" + shipment.orderId
                        + "/delivery");
        badFile.param("result", "DELIVERED");
        badFile.file(new MockMultipartFile("photos", "x.exe", "application/x-msdownload", "MZ".getBytes()));
        badFile.header(ApiHeaders.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
        asCarrier(CARRIER_A_AUTH, badFile).andExpect(status().isBadRequest());
        assertThat(value("SELECT count(*) FROM tms.order_delivery WHERE trip_id = '" + shipment.tripId + "'"))
                .isEqualTo("0");

        // Without a photo the result is still recorded: the result is the fact.
        delivery(CARRIER_A_AUTH, shipment, stopId, UUID.randomUUID().toString(), "DELIVERED", null, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.result").value("DELIVERED"))
                .andExpect(jsonPath("$.stops[0].orders[0].delivery.evidenceCount").value(0));
    }

    @Test
    @DisplayName("another carrier cannot act on the trip; the office cannot use the portal; as the runtime role nothing leaks")
    void carrierBoundary() throws Exception {
        Shipment shipment = dispatchedShipment(carrierA);
        String stopId = stopIdOf(shipment);

        // Carrier B, on carrier A's trip: one 404 for every action, and nothing written.
        stopAction(CARRIER_B_AUTH, shipment.tripId, stopId, "arrive", UUID.randomUUID().toString(), "{}")
                .andExpect(status().isNotFound());
        asCarrier(CARRIER_B_AUTH, post(PORTAL + "/" + shipment.tripId + "/incidents")
                        .header(ApiHeaders.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exceptionType\":\"TRAFFIC_DELAY\"}"))
                .andExpect(status().isNotFound());
        delivery(CARRIER_B_AUTH, shipment, stopId, UUID.randomUUID().toString(), "DELIVERED", null, false)
                .andExpect(status().isNotFound());
        assertThat(value("SELECT execution_status FROM tms.trip_stop WHERE id = '" + stopId + "'"))
                .isEqualTo("PENDING");
        assertThat(value("SELECT count(*) FROM tms.trip_exception WHERE trip_id = '" + shipment.tripId + "'"))
                .isEqualTo("0");
        // Carrier A cannot use the office's endpoints either, nor the office the portal's.
        asCarrier(CARRIER_A_AUTH, post(TRIPS + "/" + shipment.tripId + "/stops/" + stopId + "/arrive")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        asAdmin(post(PORTAL + "/" + shipment.tripId + "/stops/" + stopId + "/arrive")
                        .header(ApiHeaders.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // Carrier A records an arrival, a delivery with a photo and an incident...
        stopAction(CARRIER_A_AUTH, shipment.tripId, stopId, "arrive", UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk());
        delivery(CARRIER_A_AUTH, shipment, stopId, UUID.randomUUID().toString(), "DELIVERED", null, true)
                .andExpect(status().isOk());
        asCarrier(CARRIER_A_AUTH, post(PORTAL + "/" + shipment.tripId + "/incidents")
                        .header(ApiHeaders.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exceptionType\":\"TRAFFIC_DELAY\"}"))
                .andExpect(status().isOk());

        // ...and V70's policies keep every one of those rows away from carrier B's transactions.
        String trip = "'" + shipment.tripId + "'";
        for (String sql : List.of(
                "SELECT count(*) FROM tms.order_delivery WHERE trip_id = " + trip,
                "SELECT count(*) FROM tms.delivery_evidence e WHERE e.order_delivery_id IN"
                        + " (SELECT id FROM tms.order_delivery WHERE company_id = '" + COMPANY + "')",
                "SELECT count(*) FROM tms.transport_event WHERE trip_id = " + trip,
                "SELECT count(*) FROM tms.trip_exception WHERE trip_id = " + trip,
                "SELECT count(*) FROM tms.portal_action_receipt WHERE trip_id = " + trip)) {
            assertThat(asRuntimeRole(carrierB, sql)).as(sql).isEqualTo("0");
            assertThat(asRuntimeRole(carrierA, sql)).as(sql).isNotEqualTo("0");
            // With no carrier setting - every office request - nothing is narrowed.
            assertThat(asRuntimeRole(null, sql)).as(sql).isNotEqualTo("0");
        }
        // The delivery's lines are behind the delivery: a carrier B session cannot read them.
        execute("INSERT INTO tms.order_delivery_line (company_id, order_delivery_id, order_id, order_line_id, uom,"
                + " quantity_attempted, quantity_delivered, quantity_refused) SELECT d.company_id, d.id, d.order_id,"
                + " l.id, l.uom, 100, 100, 0 FROM tms.order_delivery d JOIN tms.transport_order_line l"
                + " ON l.order_id = d.order_id WHERE d.trip_id = " + trip);
        assertThat(asRuntimeRole(carrierB, "SELECT count(*) FROM tms.order_delivery_line WHERE order_id = '"
                + shipment.orderId + "'")).isEqualTo("0");
        assertThat(asRuntimeRole(carrierA, "SELECT count(*) FROM tms.order_delivery_line WHERE order_id = '"
                + shipment.orderId + "'")).isEqualTo("1");
        // A write under carrier B's setting against carrier A's trip is refused by the database itself.
        assertThat(rowsWrittenAsRuntimeRole(carrierB, "INSERT INTO tms.trip_exception (company_id, trip_id,"
                + " exception_type, status, reported_at, reported_by, notes) SELECT '" + COMPANY + "', " + trip
                + ", 'OTHER', 'OPEN', now(), id, 'cross-carrier' FROM tms.app_user WHERE email ="
                + " 'chofer.pod.b@example.invalid'")).isEqualTo(-1);
        assertThat(rowsWrittenAsRuntimeRole(carrierB, "UPDATE tms.order_delivery SET notes = 'tampered'"
                + " WHERE trip_id = " + trip)).isZero();
    }

    // -----------------------------------------------------------------------------------------

    private record Shipment(String tripId, String orderId, String orderNumber, String shipmentNumber,
            String orderReference, String plate) {
    }

    private Shipment dispatchedShipment(String carrier) throws Exception {
        LocalDate date = LocalDate.now(LIMA).minusDays(30).plusDays(SEQUENCE.incrementAndGet() % 25);
        String order = order(date);
        String run = newRun(date);
        String trip = newTrip(run, date, carrier);
        asAdmin(post(TRIPS + "/" + trip + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\"}")).andExpect(status().isOk());
        asAdmin(post(PLANNING + "/runs/" + run + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        Shipment shipment = new Shipment(trip, order,
                value("SELECT order_number FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'"),
                value("SELECT external_reference FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT v.license_plate FROM tms.trip t JOIN tms.vehicle v ON v.id = t.vehicle_id WHERE t.id = '"
                        + trip + "'"));
        dispatch(shipment);
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + trip + "'")).isEqualTo("IN_TRANSIT");
        return shipment;
    }

    private void dispatch(Shipment shipment) throws Exception {
        String reference = "SLS-POD-" + SEQUENCE.incrementAndGet();
        String body = """
                {"sourceSystem":"EWM_EBIM","dispatchReference":"%s","revision":1,"transportReference":"%s",
                 "loadReference":"CRG-%s","warehouseCode":"CD01","actualDispatchAt":"%s",
                 "carrier":{"code":"TRSA","name":"Transportes SA"},"vehicle":{"licensePlate":"%s","type":"FURGON"},
                 "totals":{"handlingUnits":2,"weightKg":1000},
                 "orders":[{"externalSource":"SAPB1_PE","externalReference":"%s","warehouseOrderNumber":"ORR-1",
                   "status":"SHIPPED","handlingUnits":2,"weightKg":1000,
                   "lines":[{"lineNumber":1,"materialCode":"SKU-100","quantity":100,"uom":"UN","handlingUnitCode":"HU-1"}]}],
                 "document":{"format":"SLS","version":"LOGFIRE-1","contentType":"text/plain","content":"[H1]|CD01"}}
                """.formatted(reference, shipment.shipmentNumber, reference, OffsetDateTime.now(),
                shipment.plate, shipment.orderReference);
        mockMvc.perform(post("/integration/v1/dispatch-confirmations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String stopIdOf(Shipment shipment) throws Exception {
        String detail = asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(detail, "$.stops[0].stopId");
    }

    private ResultActions stopAction(UUID auth, String tripId, String stopId, String action, String key, String body)
            throws Exception {
        AbstractMockHttpServletRequestBuilder<?> request = post(PORTAL + "/" + tripId + "/stops/" + stopId + "/"
                + action).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, key);
        }
        return asCarrier(auth, request);
    }

    private ResultActions delivery(UUID auth, Shipment shipment, String stopId, String key, String result,
            String refusedPallets, boolean withEvidence) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(PORTAL + "/" + shipment.tripId + "/stops/" + stopId
                + "/orders/" + shipment.orderId + "/delivery");
        request.param("result", result);
        if (!"NOT_ATTEMPTED".equals(result) && !"FAILED".equals(result)) {
            request.param("receiverName", "M. Quispe");
            request.param("receiverDocument", "41234567");
        }
        if (!"DELIVERED".equals(result)) {
            request.param("notes", "Dos pallets rechazados por film roto");
        }
        if (refusedPallets != null) {
            request.param("refusedPallets", refusedPallets);
        }
        if (withEvidence) {
            request.file(new MockMultipartFile("photos", "foto.jpg", "image/jpeg", "photo-bytes".getBytes()));
            request.file(new MockMultipartFile("signature", "firma.png", "image/png", "signature-bytes".getBytes()));
        }
        if (key != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, key);
        }
        return asCarrier(auth, request);
    }

    private String subscribe(String eventType) throws Exception {
        String response = asAdmin(post("/api/v1/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"WMS " + SEQUENCE.incrementAndGet() + "\","
                                + "\"targetUrl\":\"http://wms.example.invalid/tms\",\"eventTypes\":[\"" + eventType + "\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.subscription.id");
    }

    private String newRun(LocalDate date) throws Exception {
        String response = asAdmin(post(PLANNING + "/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.run.id");
    }

    private String newTrip(String runId, LocalDate date, String carrier) throws Exception {
        String departure = date.atTime(8, 0).atZone(LIMA).toOffsetDateTime().toString();
        String response = asAdmin(post(PLANNING + "/runs/" + runId + "/trips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle(carrier) + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.trip.id");
    }

    private ResultActions asAdmin(AbstractMockHttpServletRequestBuilder<?> builder) throws Exception {
        return asCarrier(ADMIN_AUTH, builder);
    }

    private ResultActions asCarrier(UUID auth, AbstractMockHttpServletRequestBuilder<?> builder) throws Exception {
        builder.header("Authorization", "Bearer " + TestJwts.validFor(auth));
        builder.header(ApiHeaders.COMPANY_ID, COMPANY.toString());
        return mockMvc.perform(builder);
    }

    private static String order(LocalDate serviceDate) {
        int number = SEQUENCE.incrementAndGet();
        String id = insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets,"
                + " customer_name, external_source, external_reference) VALUES ('" + COMPANY + "', 'TO-POD-"
                + String.format(Locale.ROOT, "%06d", number) + "', '" + origin + "', '" + destination + "', '"
                + serviceDate + "', 'READY_FOR_PLANNING', 1000, 10, 10, 'Cliente Uno SAC', 'SAPB1_PE', 'PED-POD-"
                + number + "')");
        execute("INSERT INTO tms.transport_order_line (order_id, line_number, material_code, material_description,"
                + " quantity, uom) VALUES ('" + id + "', 1, 'SKU-100', 'Cemento', 100, 'UN')");
        return id;
    }

    private static String vehicle(String carrier) {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'POD-TYPE-" + number
                + "', 'Type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'POD-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "P%02d-%03d", number % 100, number) + "', '" + carrier + "', '" + type
                + "')");
    }

    private static String client(String name, String scope) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String rowId = insertReturningId("INSERT INTO tms.integration_client (company_id, client_id, name,"
                + " secret_hash) VALUES ('" + COMPANY + "', '" + clientId + "', '" + name + "', '"
                + IntegrationSecrets.hash(secret) + "')");
        execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + rowId + "', '"
                + scope + "')");
        return IntegrationSecrets.toBearerToken(clientId, secret);
    }

    /** One statement as the runtime role, scoped to the company and (when given) a carrier. */
    private static String asRuntimeRole(String carrier, String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                scopeTo(statement, carrier);
                try (var resultSet = statement.executeQuery(sql)) {
                    resultSet.next();
                    return resultSet.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run " + sql, failed);
        }
    }

    /**
     * Rows a write touched as the runtime role, rolled back. {@code -1} when the database refused it
     * outright - the RLS {@code WITH CHECK} of an insert raises rather than skipping the row.
     */
    private static int rowsWrittenAsRuntimeRole(String carrier, String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                scopeTo(statement, carrier);
                return statement.executeUpdate(sql);
            } catch (SQLException refused) {
                if ("42501".equals(refused.getSQLState())) {
                    return -1;
                }
                throw refused;
            } finally {
                connection.rollback();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run " + sql, failed);
        }
    }

    private static void scopeTo(java.sql.Statement statement, String carrier) throws SQLException {
        statement.execute("SELECT set_config('tms.company_id', '" + COMPANY + "', true), set_config("
                + "'tms.carrier_id', '" + (carrier == null ? "" : carrier) + "', true)");
        statement.execute("SET LOCAL ROLE tms_app");
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
