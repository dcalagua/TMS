package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
 * WMS -> TMS end to end inside TMS (ADR-013, WAREHOUSE_EXECUTION_V1 §4): a warehouse credential posts
 * dispatch documents and milestones against shipments a person planned through the product's own
 * API, in each dispatch mode.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WarehouseExecutionApiIntegrationTest.JwtDecoderOverride.class)
class WarehouseExecutionApiIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";
    private static final String DISPATCHES = "/integration/v1/dispatch-confirmations";
    private static final String MILESTONES = "/integration/v1/warehouse-milestones";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("88888888-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("88888888-0000-4000-8000-0000000000c1");
    private static final UUID OTHER_COMPANY = UUID.fromString("88888888-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e1");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destination;
    private static String carrier;
    private static String warehouseToken;
    private static String warehouseClientRowId;
    private static String readOnlyToken;
    private static String otherCompanyToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_warehouse_execution");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'WEX-ORG', 'Warehouse Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'WEX-A', 'Warehouse Company', 'America/Lima'),
                    ('%s', '%s', 'WEX-B', 'Other Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'wex.admin@example.invalid', 'WEX Admin');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'wex.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'wex.admin@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, OTHER_COMPANY, ORGANIZATION, ADMIN_AUTH,
                ORGANIZATION, COMPANY, COMPANY));
        origin = insertReturningId("INSERT INTO tms.location (company_id, code, name, external_system,"
                + " external_reference) VALUES ('" + COMPANY + "', 'CD-LIMA', 'CD Lima', 'EWM', 'CD01')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + origin + "', 'ORIGIN')");
        destination = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'SODIMAC-1', 'Sodimac 1')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + destination + "', 'DESTINATION')");
        carrier = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY + "', 'TRSA', 'Transportes SA', 'RUC', '20100000019')");

        String[] warehouse = client(COMPANY, "EWM warehouse", "integration.dispatch:write",
                "integration.warehouse-milestone:write", "integration.shipment:read");
        warehouseToken = warehouse[0];
        warehouseClientRowId = warehouse[1];
        readOnlyToken = client(COMPANY, "Read only", "integration.shipment:read")[0];
        otherCompanyToken = client(OTHER_COMPANY, "Other EWM", "integration.dispatch:write")[0];
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

    @BeforeEach
    void mintToken() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        mode("MANUAL");
    }

    // --- EXTERNAL_REQUIRED ---------------------------------------------------------------------

    @Test
    @DisplayName("EXTERNAL_REQUIRED: an SLS for a ready trip dispatches it, attributed to the credential, 97/100 is a MISMATCH")
    void externalRequiredAppliesAndReconciles() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");

        // After the trip was made ready (V25: a departure never precedes it), inside the 5-minute tolerance.
        OffsetDateTime at = OffsetDateTime.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, at, "CD01", "97"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.verificationStatus").value("MISMATCH"))
                .andExpect(jsonPath("$.discrepancies[0].code").value("QUANTITY_VARIANCE"))
                .andExpect(jsonPath("$.discrepancies[0].planned").value(100))
                .andExpect(jsonPath("$.discrepancies[0].dispatched").value(97));

        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("IN_TRANSIT");
        assertThat(value("SELECT dispatch_source FROM tms.trip WHERE id = '" + shipment.tripId + "'"))
                .isEqualTo("INTEGRATION");
        assertThat(value("SELECT dispatched_by_client::text FROM tms.trip WHERE id = '" + shipment.tripId + "'"))
                .isEqualTo(warehouseClientRowId);
        assertThat(value("SELECT actual_departure_at = '" + at + "'::timestamptz FROM tms.trip WHERE id = '"
                + shipment.tripId + "'")).isEqualTo("t");
        assertThat(value("SELECT status FROM tms.transport_order WHERE id = '" + shipment.orderId + "'"))
                .isEqualTo("IN_EXECUTION");
        // The plan is not rewritten: the assignment still says 100.
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'WAREHOUSE_DISPATCH_CONFIRMED' AND source = 'INTEGRATION'")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'TRIP_DISPATCHED' AND source = 'INTEGRATION'")).isEqualTo("1");
        assertThat(value("SELECT raw_payload LIKE '%\"dispatchReference\"%' FROM tms.external_dispatch WHERE trip_id = '"
                + shipment.tripId + "'")).isEqualTo("t");

        // The trip workspace card reads it back, plan and reality side by side.
        String card = mockMvc.perform(asAdmin(get(TRIPS + "/" + shipment.tripId + "/warehouse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchSource").value("INTEGRATION"))
                .andExpect(jsonPath("$.verificationStatus").value("MISMATCH"))
                .andExpect(jsonPath("$.documents[0].outcome").value("APPLIED"))
                .andExpect(jsonPath("$.documents[0].orders[0].matchResult").value("VARIANCE"))
                .andExpect(jsonPath("$.documents[0].discrepancies[0].code").value("QUANTITY_VARIANCE"))
                .andReturn().getResponse().getContentAsString();
        String documentId = JsonPath.read(card, "$.documents[0].id");
        mockMvc.perform(asAdmin(get(TRIPS + "/" + shipment.tripId + "/warehouse/documents/" + documentId + "/raw")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("someFutureField")));

        // And the Control Tower raises it for the day.
        String planningDate = value("SELECT planning_date::text FROM tms.trip WHERE id = '" + shipment.tripId + "'");
        mockMvc.perform(asAdmin(get("/api/v1/monitoring/control-tower")).param("date", planningDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories[?(@.type == 'DISPATCH_MISMATCH' && @.tripId == '"
                        + shipment.tripId + "')]").exists());
    }

    @Test
    @DisplayName("EXTERNAL_REQUIRED: an SLS for a CONFIRMED trip takes ready and dispatch in one transaction")
    void externalRequiredFromConfirmed() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = confirmedShipment("100");

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"));

        assertThat(value("SELECT status || '/' || (ready_by_client IS NOT NULL) || '/' || (ready_by IS NULL)"
                + " FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("IN_TRANSIT/true/true");
    }

    @Test
    @DisplayName("EXTERNAL_REQUIRED: a person without an override gets the specific 409; the SLS still dispatches after")
    void externalRequiredRefusesAPlainManualDispatch() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");

        transition(shipment.tripId, "dispatch", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispatch-requires-external-confirmation"));

        transition(shipment.tripId, "dispatch", "WMS down since 06:00")
                .andExpect(status().isOk());
        assertThat(value("SELECT dispatch_source FROM tms.trip WHERE id = '" + shipment.tripId + "'"))
                .isEqualTo("OPERATOR_OVERRIDE");
        assertThat(value("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + shipment.tripId
                + "' AND action = 'DISPATCH_OVERRIDDEN'")).isEqualTo("1");

        // The SLS that arrives afterwards only reconciles, and says the trip was overridden.
        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECONCILED"))
                .andExpect(jsonPath("$.verificationStatus").value("OVERRIDDEN"));
    }

    // --- HYBRID and MANUAL ---------------------------------------------------------------------

    @Test
    @DisplayName("HYBRID: a person dispatches first, the SLS reconciles only - one dispatch, DISPATCH_TIME past 15 minutes")
    void hybridManualFirst() throws Exception {
        mode("HYBRID");
        Shipment shipment = readyShipment("100");
        transition(shipment.tripId, "dispatch", null).andExpect(status().isOk());
        String departedAt = value("SELECT actual_departure_at::text FROM tms.trip WHERE id = '" + shipment.tripId + "'");

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now().minusMinutes(40),
                "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECONCILED"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"))
                .andExpect(jsonPath("$.discrepancies[0].code").value("DISPATCH_TIME"));

        assertThat(value("SELECT dispatch_source FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("OPERATOR");
        assertThat(value("SELECT actual_departure_at::text FROM tms.trip WHERE id = '" + shipment.tripId + "'"))
                .as("the recorded departure is kept; the SLS time lives on the document").isEqualTo(departedAt);
    }

    @RepeatedTest(6)
    @DisplayName("HYBRID: a person and an SLS at the same instant - exactly one dispatch")
    void hybridConcurrent() throws Exception {
        mode("HYBRID");
        Shipment shipment = readyShipment("100");
        String body = document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100");
        long version = tripVersion(shipment.tripId);

        List<Integer> statuses = concurrently(
                () -> mockMvc.perform(asAdmin(post(TRIPS + "/" + shipment.tripId + "/dispatch"))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                        .andReturn().getResponse().getStatus(),
                () -> dispatch(body).andReturn().getResponse().getStatus());

        assertThat(statuses.get(1)).isEqualTo(201);
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("IN_TRANSIT");
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'TRIP_DISPATCHED'")).isEqualTo("1");
        assertThat(value("SELECT outcome FROM tms.external_dispatch WHERE trip_id = '" + shipment.tripId + "'"))
                .isIn("APPLIED", "RECONCILED");
    }

    @Test
    @DisplayName("MANUAL: the SLS is stored and reconciled and never moves the trip")
    void manualNeverMoves() throws Exception {
        Shipment shipment = readyShipment("100");

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECONCILED"));

        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("READY_FOR_DISPATCH");
    }

    // --- idempotency and revisions ---------------------------------------------------------------

    @Test
    @DisplayName("the same SLS twice: 200 UNCHANGED, one document, one dispatch; other content under the same revision is 409")
    void duplicateSls() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");
        String reference = "SLS-" + SEQUENCE.incrementAndGet();
        OffsetDateTime at = OffsetDateTime.now();
        String body = document(shipment, reference, 1, at, "CD01", "100");

        dispatch(body).andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        dispatch(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        // The same content with other whitespace is the same delivery.
        dispatch(body.replace(",", " ,  ")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCHANGED"));

        assertThat(value("SELECT count(*) FROM tms.external_dispatch WHERE dispatch_reference = '" + reference + "'"))
                .isEqualTo("1");
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'TRIP_DISPATCHED'")).isEqualTo("1");

        dispatch(document(shipment, reference, 1, at, "CD01", "99"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("revision 2 supersedes revision 1 and reconciles again without a second dispatch; an older revision is STALE")
    void revisions() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");
        String reference = "SLS-" + SEQUENCE.incrementAndGet();
        OffsetDateTime at = OffsetDateTime.now();

        dispatch(document(shipment, reference, 1, at, "CD01", "97")).andExpect(status().isCreated());
        dispatch(document(shipment, reference, 2, at, "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECONCILED"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"));
        dispatch(document(shipment, reference, 1, at, "CD01", "98"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STALE"))
                .andExpect(jsonPath("$.revision").value(2));

        assertThat(value("SELECT string_agg(revision || ':' || (superseded_at IS NULL), ',' ORDER BY revision)"
                + " FROM tms.external_dispatch WHERE dispatch_reference = '" + reference + "'")).isEqualTo("1:false,2:true");
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'TRIP_DISPATCHED'")).isEqualTo("1");
    }

    // --- facts TMS cannot apply -------------------------------------------------------------------

    @Test
    @DisplayName("a cancelled trip: the SLS is recorded UNAPPLIED with TRIP_CANCELLED, never refused")
    void cancelledTrip() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");
        mockMvc.perform(asAdmin(post(TRIPS + "/" + shipment.tripId + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + tripVersion(shipment.tripId) + ",\"reason\":\"no truck\"}"))
                .andExpect(status().isOk());

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("UNAPPLIED"))
                .andExpect(jsonPath("$.discrepancies[?(@.code == 'TRIP_CANCELLED')]").exists());
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("an SLS stamped before the trip was made ready is UNAPPLIED with NOT_APPLIED: no time is invented")
    void dispatchedBeforeReady() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now().minusHours(2),
                "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("UNAPPLIED"))
                .andExpect(jsonPath("$.discrepancies[?(@.code == 'NOT_APPLIED')]").exists())
                // NOT_APPLIED is an ERROR (contract §4.3): the document is a MISMATCH, and a person is
                // asked to resolve it from the Control Tower (ADR-013 section 3).
                .andExpect(jsonPath("$.verificationStatus").value("MISMATCH"));
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("READY_FOR_DISPATCH");
        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'WAREHOUSE_DISPATCH_CONFIRMED'")).isEqualTo("1");
        String planningDate = value("SELECT planning_date::text FROM tms.trip WHERE id = '" + shipment.tripId + "'");
        mockMvc.perform(asAdmin(get("/api/v1/monitoring/control-tower")).param("date", planningDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories[?(@.type == 'DISPATCH_MISMATCH' && @.tripId == '"
                        + shipment.tripId + "')]").exists());
    }

    @Test
    @DisplayName("an unknown shipment is RECORDED_UNMATCHED, and another company's credential cannot reach this one's")
    void unmatchedAndTenantIsolation() throws Exception {
        mode("EXTERNAL_REQUIRED");
        Shipment shipment = readyShipment("100");
        String body = document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100");

        mockMvc.perform(post(DISPATCHES).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherCompanyToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECORDED_UNMATCHED"))
                .andExpect(jsonPath("$.discrepancies[0].code").value("UNKNOWN_TRANSPORT_REFERENCE"));
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("READY_FOR_DISPATCH");
    }

    @Test
    @DisplayName("a wrong warehouse is a MISMATCH, not a refusal")
    void wrongWarehouse() throws Exception {
        mode("HYBRID");
        Shipment shipment = readyShipment("100");

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD99", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.verificationStatus").value("MISMATCH"))
                .andExpect(jsonPath("$.discrepancies[0].code").value("WAREHOUSE_MISMATCH"));
    }

    @Test
    @DisplayName("split order: the SLS dispatches 60 of 100 and the remaining 40 stay plannable")
    void splitOrder() throws Exception {
        mode("EXTERNAL_REQUIRED");
        LocalDate date = nextDate();
        String order = order(date, "100");
        String run = newRun(date);
        String trip = newTrip(run, date);
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order + "\",\"weightKg\":600,\"volumeM3\":6,\"pallets\":60}"))
                .andExpect(status().isOk());
        confirm(run);
        transition(trip, "ready", null).andExpect(status().isOk());
        Shipment shipment = new Shipment(trip, order, value("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'"),
                value("SELECT external_reference FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT v.license_plate FROM tms.trip t JOIN tms.vehicle v ON v.id = t.vehicle_id WHERE t.id = '"
                        + trip + "'"));

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "60"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + trip + "'")).isEqualTo("IN_TRANSIT");
        assertThat(value("SELECT status || '/' || (total_pallets - allocated_pallets) FROM tms.transport_order WHERE id = '"
                + order + "'")).isEqualTo("READY_FOR_PLANNING/40.00");
    }

    // --- milestones and scopes --------------------------------------------------------------------

    @Test
    @DisplayName("milestones: RECORDED on the timeline, DUPLICATE by eventId, UNKNOWN_SHIPMENT kept, INVALID -> 207; no lifecycle moves")
    void milestones() throws Exception {
        Shipment shipment = readyShipment("100");
        String eventId = "evt-" + UUID.randomUUID();
        String body = """
                {"sourceSystem":"EWM_EBIM","milestones":[
                  {"eventId":"%s","type":"LOADING_STARTED","transportReference":"%s","loadReference":"CRG-1",
                   "warehouseCode":"CD01","occurredAt":"%s"},
                  {"eventId":"%s","type":"LOADING_STARTED","transportReference":"%s","occurredAt":"%s"},
                  {"eventId":"evt-%s","type":"LOAD_READY","transportReference":"SH-NOPE","occurredAt":"%s"},
                  {"eventId":"evt-%s","type":"TELEPORTED","transportReference":"%s","occurredAt":"%s"}
                ]}
                """.formatted(eventId, shipment.shipmentNumber, OffsetDateTime.now().minusMinutes(30),
                eventId, shipment.shipmentNumber, OffsetDateTime.now().minusMinutes(30),
                UUID.randomUUID(), OffsetDateTime.now().minusMinutes(20),
                UUID.randomUUID(), shipment.shipmentNumber, OffsetDateTime.now().minusMinutes(10));

        mockMvc.perform(post(MILESTONES).header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.results[0].result").value("RECORDED"))
                .andExpect(jsonPath("$.results[1].result").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[2].result").value("UNKNOWN_SHIPMENT"))
                .andExpect(jsonPath("$.results[3].result").value("INVALID"));

        assertThat(value("SELECT count(*) FROM tms.transport_event WHERE trip_id = '" + shipment.tripId
                + "' AND event_type = 'WAREHOUSE_LOADING_STARTED' AND source = 'INTEGRATION'")).isEqualTo("1");
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("READY_FOR_DISPATCH");
    }

    @Test
    @DisplayName("a credential without integration.dispatch:write is refused 403, and a malformed document 400")
    void scopesAndValidation() throws Exception {
        Shipment shipment = readyShipment("100");
        mockMvc.perform(post(DISPATCHES).header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnlyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(document(shipment, "SLS-X", 1, OffsetDateTime.now(), "CD01", "100")))
                .andExpect(status().isForbidden());
        dispatch("{\"sourceSystem\":\"ewm lower\",\"dispatchReference\":\"SLS-Y\",\"revision\":0}")
                .andExpect(status().isBadRequest());
        dispatch("{not json").andExpect(status().isBadRequest());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Shipment(String tripId, String orderId, String shipmentNumber, String orderReference, String plate) {
    }

    private Shipment readyShipment(String pallets) throws Exception {
        Shipment shipment = confirmedShipment(pallets);
        transition(shipment.tripId, "ready", null).andExpect(status().isOk());
        return shipment;
    }

    private Shipment confirmedShipment(String pallets) throws Exception {
        LocalDate date = nextDate();
        String order = order(date, pallets);
        String run = newRun(date);
        String trip = newTrip(run, date);
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order + "\"}"))
                .andExpect(status().isOk());
        confirm(run);
        return new Shipment(trip, order, value("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'"),
                value("SELECT external_reference FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT v.license_plate FROM tms.trip t JOIN tms.vehicle v ON v.id = t.vehicle_id WHERE t.id = '"
                        + trip + "'"));
    }

    /** An SLS for the shipment's only order, one line of 100 UN planned; {@code quantity} dispatched. */
    private static String document(Shipment shipment, String reference, int revision, OffsetDateTime at,
            String warehouse, String quantity) {
        return """
                {"sourceSystem":"EWM_EBIM","dispatchReference":"%s","revision":%d,"transportReference":"%s",
                 "loadReference":"CRG-%s","warehouseCode":"%s","actualDispatchAt":"%s",
                 "carrier":{"code":"TRSA","name":"Transportes SA"},"vehicle":{"licensePlate":"%s","type":"FURGON"},
                 "totals":{"handlingUnits":2,"weightKg":1000},
                 "orders":[{"externalSource":"SAPB1_PE","externalReference":"%s","warehouseOrderNumber":"ORR-1",
                   "status":"SHIPPED","handlingUnits":2,"weightKg":1000,
                   "lines":[{"lineNumber":1,"materialCode":"SKU-100","quantity":%s,"uom":"UN","handlingUnitCode":"HU-1"}]}],
                 "document":{"format":"SLS","version":"LOGFIRE-1","contentType":"text/plain","content":"[H1]|CD01"},
                 "someFutureField":"ignored"}
                """.formatted(reference, revision, shipment.shipmentNumber, reference, warehouse, at, shipment.plate,
                shipment.orderReference, quantity);
    }

    private ResultActions dispatch(String body) throws Exception {
        return mockMvc.perform(post(DISPATCHES).header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions transition(String tripId, String action, String overrideReason) throws Exception {
        String reason = overrideReason == null ? "" : ",\"overrideReason\":\"" + overrideReason + "\"";
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + tripVersion(tripId) + reason + "}"));
    }

    private long tripVersion(String tripId) throws Exception {
        String body = mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(body, "$.trip.version");
        return version.longValue();
    }

    private void confirm(String runId) throws Exception {
        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + runId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
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

    private static void mode(String mode) {
        execute("INSERT INTO tms.company_settings (company_id, dispatch_confirmation_mode) VALUES ('" + COMPANY
                + "', '" + mode + "') ON CONFLICT (company_id) DO UPDATE SET dispatch_confirmation_mode = EXCLUDED."
                + "dispatch_confirmation_mode");
    }

    private static LocalDate nextDate() {
        return LocalDate.now(LIMA).minusDays(60).plusDays(SEQUENCE.incrementAndGet());
    }

    /** A released order of {@code pallets} pallets with one line of 100 UN, keyed SAPB1_PE. */
    private static String order(LocalDate serviceDate, String pallets) {
        int number = SEQUENCE.incrementAndGet();
        String id = insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets,"
                + " external_source, external_reference) VALUES ('" + COMPANY + "', 'TO-WEX-"
                + String.format(Locale.ROOT, "%06d", number) + "', '" + origin + "', '" + destination + "', '"
                + serviceDate + "', 'READY_FOR_PLANNING', 1000, 10, " + pallets + ", 'SAPB1_PE', 'PED-WEX-" + number + "')");
        execute("INSERT INTO tms.transport_order_line (order_id, line_number, material_code, material_description, quantity, uom)"
                + " VALUES ('" + id + "', 1, 'SKU-100', 'Cemento', 100, 'UN')");
        return id;
    }

    private static String vehicle() {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'WEX-TYPE-" + number
                + "', 'Type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'WEX-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "W%02d-%03d", number % 100, number) + "', '" + carrier + "', '" + type + "')");
    }

    @SafeVarargs
    private static List<Integer> concurrently(Callable<Integer>... calls) throws Exception {
        CyclicBarrier together = new CyclicBarrier(calls.length);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Callable<Integer>> started = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                started.add(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return call.call();
                });
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(started)) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
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
