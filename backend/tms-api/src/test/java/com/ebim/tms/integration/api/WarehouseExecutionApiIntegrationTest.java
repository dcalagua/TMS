package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.application.DispatchConfirmationV1;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
import tools.jackson.databind.ObjectMapper;

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
    private static final String SHIPMENTS = "/integration/v1/shipments";
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

    @Autowired
    private ObjectMapper objectMapper;

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
        // Webhooks on, so the plan v1.1 test can prove DELIVERY_RESULT_RECORDED reaches a
        // subscription. The target is never called: the dispatcher's first pass is an hour away.
        registry.add("tms.integration.webhooks.secret-key", () -> "wex-test-webhook-key-0123456789abcdef");
        registry.add("tms.integration.webhooks.allow-insecure-targets", () -> "true");
        registry.add("tms.integration.webhooks.allow-private-network-targets", () -> "true");
        registry.add("tms.integration.webhooks.poll-interval", () -> "1h");
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

    // --- contract v1.1 (§7.3): trailer plate and per-order geometry --------------------------------

    @Test
    @DisplayName("v1.1: the trailer plate and the per-order geometry are stored and shown; another trailer is no mismatch")
    void dispatchV11FieldsStored() throws Exception {
        mode("HYBRID");
        Shipment shipment = readyShipment("100");
        String reference = "SLS-" + SEQUENCE.incrementAndGet();

        dispatch(documentV11(shipment, reference, shipment.plate, "C4X-991",
                "\"stacks\":10,\"effectiveVolumeM3\":0.1503,\"loadingMeters\":0.05,\"geometryReason\":null"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"))
                .andExpect(jsonPath("$.discrepancies[?(@.code == 'VEHICLE_MISMATCH')]").doesNotExist());

        assertThat(value("SELECT vehicle_trailer_plate FROM tms.external_dispatch WHERE dispatch_reference = '"
                + reference + "'")).isEqualTo("C4X-991");
        assertThat(value("SELECT o.stacks || ':' || o.effective_volume_m3 || ':' || o.loading_meters || ':'"
                + " || coalesce(o.geometry_reason, '-') FROM tms.external_dispatch_order o JOIN tms.external_dispatch d"
                + " ON d.id = o.external_dispatch_id WHERE d.dispatch_reference = '" + reference + "'"))
                .isEqualTo("10:0.1503:0.050:-");

        String view = mockMvc.perform(asAdmin(get(TRIPS + "/" + shipment.tripId + "/warehouse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].vehicleTrailerPlate").value("C4X-991"))
                .andExpect(jsonPath("$.documents[0].orders[0].stacks").value(10))
                .andExpect(jsonPath("$.documents[0].orders[0].geometryReason").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat(number(view, "$.documents[0].orders[0].effectiveVolumeM3")).isEqualByComparingTo("0.1503");
        assertThat(number(view, "$.documents[0].orders[0].loadingMeters")).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("v1.1: VEHICLE_MISMATCH compares only licensePlate, even when the trailer carries the planned plate")
    void dispatchV11VehicleMismatchOnTractorOnly() throws Exception {
        mode("HYBRID");
        Shipment shipment = readyShipment("100");
        String reference = "SLS-" + SEQUENCE.incrementAndGet();

        dispatch(documentV11(shipment, reference, "ZZZ-999", shipment.plate,
                "\"stacks\":null,\"geometryReason\":\"NEST_INCREMENT_MISSING\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationStatus").value("MISMATCH"))
                .andExpect(jsonPath("$.discrepancies[?(@.code == 'VEHICLE_MISMATCH')]").exists())
                .andExpect(jsonPath("$.discrepancies.length()").value(1));
        assertThat(value("SELECT coalesce(o.stacks::text, '-') || ':' || coalesce(o.effective_volume_m3::text, '-')"
                + " || ':' || o.geometry_reason FROM tms.external_dispatch_order o JOIN tms.external_dispatch d"
                + " ON d.id = o.external_dispatch_id WHERE d.dispatch_reference = '" + reference + "'"))
                .isEqualTo("-:-:NEST_INCREMENT_MISSING");
    }

    @Test
    @DisplayName("v1.1: a v1 body stores no v1.1 field and fingerprints exactly as before; bad v1.1 values are a 400")
    void dispatchV1BodyUnchanged() throws Exception {
        mode("HYBRID");
        Shipment shipment = readyShipment("100");
        String reference = "SLS-" + SEQUENCE.incrementAndGet();
        String body = document(shipment, reference, 1, OffsetDateTime.now(), "CD01", "100");

        dispatch(body).andExpect(status().isCreated()).andExpect(jsonPath("$.verificationStatus").value("MATCHED"));
        dispatch(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        assertThat(value("SELECT coalesce(d.vehicle_trailer_plate, '-') || ':' || coalesce(o.stacks::text, '-') || ':'"
                + " || coalesce(o.effective_volume_m3::text, '-') || ':' || coalesce(o.loading_meters::text, '-') || ':'"
                + " || coalesce(o.geometry_reason, '-') FROM tms.external_dispatch_order o JOIN tms.external_dispatch d"
                + " ON d.id = o.external_dispatch_id WHERE d.dispatch_reference = '" + reference + "'"))
                .isEqualTo("-:-:-:-:-");

        // The idempotency fingerprint is the re-serialised request: a v1 body must not grow the
        // v1.1 keys, or a document stored before V60 would be refused as different content.
        String canonical = objectMapper.writeValueAsString(objectMapper.readValue(body, DispatchConfirmationV1.class));
        assertThat(canonical).doesNotContain("trailerLicensePlate", "stacks", "effectiveVolumeM3", "loadingMeters",
                "geometryReason");

        dispatch(documentV11(shipment, "SLS-" + SEQUENCE.incrementAndGet(), shipment.plate, "C4X-991",
                "\"stacks\":-1"))
                .andExpect(status().isBadRequest());
    }

    // --- phase 45: the warehouse's footprint compared, and the plan's footprint priced -------------

    @Test
    @DisplayName("v1.1 compared: a different footprint is three INFO GEOMETRY_VARIANCE lines, MATCHED and APPLIED, shown on the trip")
    void dispatchGeometryVarianceIsInformative() throws Exception {
        mode("HYBRID");
        Shipment shipment = measuredReadyShipment();
        String reference = "SLS-" + SEQUENCE.incrementAndGet();

        // TMS planned 10 stacks, 0.1503 m3 and 0.1103 / (2.40 x 0.85) = 0.054 loading metres.
        String response = dispatch(documentV11(shipment, reference, shipment.plate, "C4X-991",
                "\"stacks\":7,\"effectiveVolumeM3\":0.18,\"loadingMeters\":0.08,\"geometryReason\":null"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"))
                .andExpect(jsonPath("$.discrepancies.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(response, "$.discrepancies[*].code");
        List<String> severities = JsonPath.read(response, "$.discrepancies[*].severity");
        List<String> units = JsonPath.read(response, "$.discrepancies[*].uom");
        assertThat(codes).containsOnly("GEOMETRY_VARIANCE");
        assertThat(severities).containsOnly("INFO");
        assertThat(units).containsExactly("STACKS", "M3", "LDM");
        assertThat(number(response, "$.discrepancies[2].planned")).isEqualByComparingTo("0.054");
        assertThat(value("SELECT status FROM tms.trip WHERE id = '" + shipment.tripId + "'")).isEqualTo("IN_TRANSIT");

        mockMvc.perform(asAdmin(get(TRIPS + "/" + shipment.tripId + "/warehouse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].verificationStatus").value("MATCHED"))
                .andExpect(jsonPath("$.documents[0].orders[0].matchResult").value("MATCHED"))
                .andExpect(jsonPath("$.documents[0].discrepancies[?(@.code == 'GEOMETRY_VARIANCE')]").exists());
    }

    @Test
    @DisplayName("v1 against a measured plan: no geometry is compared, MATCHED with no discrepancy (compatibility)")
    void dispatchV1AgainstMeasuredPlan() throws Exception {
        mode("HYBRID");
        Shipment shipment = measuredReadyShipment();

        dispatch(document(shipment, "SLS-" + SEQUENCE.incrementAndGet(), 1, OffsetDateTime.now(), "CD01", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationStatus").value("MATCHED"))
                .andExpect(jsonPath("$.discrepancies.length()").value(0));
        // The same footprint TMS planned is no difference either.
        Shipment second = measuredReadyShipment();
        dispatch(documentV11(second, "SLS-" + SEQUENCE.incrementAndGet(), second.plate, "C4X-991",
                "\"stacks\":10,\"effectiveVolumeM3\":0.1503,\"loadingMeters\":null,\"geometryReason\":null"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discrepancies.length()").value(0));
    }

    @Test
    @DisplayName("V63: a card per loading metre prices the measured shipment on effective geometry; an unmeasured one is NOT_CALCULABLE")
    void loadingMeterRate() throws Exception {
        Shipment measured = measuredReadyShipment();
        String vehicleType = value("SELECT v.vehicle_type_id FROM tms.trip t JOIN tms.vehicle v ON v.id = t.vehicle_id"
                + " WHERE t.id = '" + measured.tripId + "'");
        mockMvc.perform(asAdmin(post("/api/v1/rates/rate-cards")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LDM-%d","name":"Per loading metre","carrierId":"%s","scope":"CARRIER",
                                 "vehicleTypeId":"%s","currency":"PEN","validFrom":"%s",
                                 "amountPerM3":100,"amountPerLoadingMeter":50}
                                """.formatted(SEQUENCE.incrementAndGet(), carrier, vehicleType,
                                LocalDate.now(LIMA).minusYears(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPerLoadingMeter").value(50));

        String priced = mockMvc.perform(asAdmin(post("/api/v1/rates/trips/" + measured.tripId + "/cost/estimate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[?(@.component == 'LOADING_METERS')].unit").value("LOADING_METER"))
                .andExpect(jsonPath("$.components[?(@.component == 'LOADING_METERS')].quantitySource")
                        .value("EFFECTIVE_GEOMETRY"))
                .andExpect(jsonPath("$.components[?(@.component == 'VOLUME')].quantitySource")
                        .value("EFFECTIVE_GEOMETRY"))
                .andReturn().getResponse().getContentAsString();
        assertThat(componentNumber(priced, "LOADING_METERS", "quantity"))
                .isEqualByComparingTo("0.054");
        assertThat(componentNumber(priced, "LOADING_METERS", "amount"))
                .isEqualByComparingTo("2.70");
        assertThat(componentNumber(priced, "VOLUME", "amount"))
                .isEqualByComparingTo("15.03");
        assertThat(number(priced, "$.estimatedAmount")).isEqualByComparingTo("17.73");

        // The same deck, an order nobody measured: the declared 10 m3 and no floor to measure.
        Shipment unmeasured = readyShipmentOn(vehicleOfType(vehicleType), false);
        mockMvc.perform(asAdmin(post("/api/v1/rates/trips/" + unmeasured.tripId + "/cost/estimate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimateComplete").value(false))
                .andExpect(jsonPath("$.components[?(@.component == 'LOADING_METERS')].status").value("NOT_CALCULABLE"))
                .andExpect(jsonPath("$.components[?(@.component == 'LOADING_METERS')].reason").value("FLOOR_AREA_UNKNOWN"))
                .andExpect(jsonPath("$.components[?(@.component == 'VOLUME')].quantitySource")
                        .value("ORDER_DECLARED_TOTALS"));
        assertThat(value("SELECT count(*) FROM tms.trip_cost_component c JOIN tms.trip_cost t ON t.id = c.trip_cost_id"
                + " WHERE t.trip_id IN ('" + measured.tripId + "', '" + unmeasured.tripId + "')"
                + " AND c.component = 'LOADING_METERS'")).isEqualTo("2");
    }

    // --- plan v1.1 (§7.2): orders[].delivery and DELIVERY_RESULT_RECORDED ------------------------

    @Test
    @DisplayName("plan v1.1: delivery is null until a result is recorded, then carries the blocks and lines; the webhook is fanned out")
    void planDeliveryBlock() throws Exception {
        String subscription = subscribe("DELIVERY_RESULT_RECORDED");
        Shipment shipment = readyShipment("100");
        transition(shipment.tripId, "dispatch", null).andExpect(status().isOk());

        plan(shipment)
                .andExpect(jsonPath("$.orders[0].deliveryResult").value(nullValue()))
                .andExpect(jsonPath("$.orders[0].delivery").value(nullValue()));

        String stop = stopIdsOf(shipment.tripId).get(0);
        stopAction(shipment.tripId, stop, "arrive").andExpect(status().isOk());
        recordDelivery(shipment, stop, """
                {"result":"PARTIAL","deliveredAt":"%s","receiverName":"M. Quispe","notes":"10 refused",
                 "quantities":{"attemptedWeightKg":1000,"attemptedVolumeM3":10,"attemptedPallets":100,
                   "deliveredWeightKg":900,"deliveredVolumeM3":9,"deliveredPallets":90,
                   "refusedWeightKg":100,"refusedVolumeM3":1,"refusedPallets":10}}
                """.formatted(OffsetDateTime.now()));
        // No API writes per-line results yet (V45 table only); the plan reads whatever is there.
        execute("INSERT INTO tms.order_delivery_line (company_id, order_delivery_id, order_id, order_line_id, uom,"
                + " quantity_attempted, quantity_delivered, quantity_refused) SELECT d.company_id, d.id, d.order_id,"
                + " l.id, l.uom, 100, 90, 10 FROM tms.order_delivery d JOIN tms.transport_order_line l"
                + " ON l.order_id = d.order_id WHERE d.order_id = '" + shipment.orderId + "'");

        String body = plan(shipment)
                // The flat v1 fields are unchanged beside the block.
                .andExpect(jsonPath("$.orders[0].deliveryResult").value("PARTIAL"))
                .andExpect(jsonPath("$.orders[0].deliveryReceiverName").value("M. Quispe"))
                .andExpect(jsonPath("$.orders[0].delivery.result").value("PARTIAL"))
                .andExpect(jsonPath("$.orders[0].delivery.receiverName").value("M. Quispe"))
                .andExpect(jsonPath("$.orders[0].delivery.evidenceCount").value(0))
                .andExpect(jsonPath("$.orders[0].delivery.deliveredAt").exists())
                .andExpect(jsonPath("$.orders[0].delivery.lines.length()").value(1))
                .andExpect(jsonPath("$.orders[0].delivery.lines[0].lineNumber").value(1))
                .andExpect(jsonPath("$.orders[0].delivery.lines[0].materialCode").value("SKU-100"))
                .andExpect(jsonPath("$.orders[0].delivery.lines[0].uom").value("UN"))
                .andExpect(jsonPath("$.orders[0].geometry").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String block = "$.orders[0].delivery.";
        assertThat(number(body, block + "attempted.weightKg")).isEqualByComparingTo("1000");
        assertThat(number(body, block + "attempted.volumeM3")).isEqualByComparingTo("10");
        assertThat(number(body, block + "attempted.pallets")).isEqualByComparingTo("100");
        assertThat(number(body, block + "delivered.weightKg")).isEqualByComparingTo("900");
        assertThat(number(body, block + "delivered.pallets")).isEqualByComparingTo("90");
        assertThat(number(body, block + "refused.weightKg")).isEqualByComparingTo("100");
        assertThat(number(body, block + "refused.volumeM3")).isEqualByComparingTo("1");
        assertThat(number(body, block + "lines[0].quantityAttempted")).isEqualByComparingTo("100");
        assertThat(number(body, block + "lines[0].quantityDelivered")).isEqualByComparingTo("90");
        assertThat(number(body, block + "lines[0].quantityRefused")).isEqualByComparingTo("10");

        // The same signed webhook as the other events: one delivery queued for the subscriber,
        // a trigger naming the shipment, which the WMS answers by pulling this plan.
        assertThat(value("SELECT count(*) FROM tms.webhook_delivery WHERE webhook_subscription_id = '" + subscription
                + "' AND event_type = 'DELIVERY_RESULT_RECORDED' AND payload LIKE '%\"" + shipment.shipmentNumber
                + "\"%'")).isEqualTo("1");
        assertThat(value("SELECT payload LIKE '%\"type\":\"DELIVERY_RESULT_RECORDED\"%' FROM tms.webhook_delivery"
                + " WHERE webhook_subscription_id = '" + subscription + "' AND payload LIKE '%\""
                + shipment.shipmentNumber + "\"%'")).isEqualTo("t");
    }

    @Test
    @DisplayName("plan v1.1: a result recorded without amounts has null blocks - never zero - and no lines")
    void planDeliveryWithoutAmounts() throws Exception {
        Shipment shipment = readyShipment("100");
        transition(shipment.tripId, "dispatch", null).andExpect(status().isOk());
        String stop = stopIdsOf(shipment.tripId).get(0);
        stopAction(shipment.tripId, stop, "arrive").andExpect(status().isOk());
        recordDelivery(shipment, stop, "{\"result\":\"DELIVERED\",\"deliveredAt\":\"" + OffsetDateTime.now() + "\"}");

        plan(shipment)
                .andExpect(jsonPath("$.orders[0].deliveryResult").value("DELIVERED"))
                .andExpect(jsonPath("$.orders[0].delivery.result").value("DELIVERED"))
                .andExpect(jsonPath("$.orders[0].delivery.receiverName").value(nullValue()))
                .andExpect(jsonPath("$.orders[0].delivery.attempted").value(nullValue()))
                .andExpect(jsonPath("$.orders[0].delivery.delivered").value(nullValue()))
                .andExpect(jsonPath("$.orders[0].delivery.refused").value(nullValue()))
                .andExpect(jsonPath("$.orders[0].delivery.lines").isEmpty());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Shipment(String tripId, String orderId, String shipmentNumber, String orderReference, String plate) {
    }

    private Shipment readyShipment(String pallets) throws Exception {
        Shipment shipment = confirmedShipment(pallets);
        transition(shipment.tripId, "ready", null).andExpect(status().isOk());
        return shipment;
    }

    /**
     * A ready shipment on a 7.20 x 2.40 x 2.40 deck filled to 0.85, carrying one order of PLP-571
     * measured as ADR-022 measures it: 10 stacks, 0.1503 m3 effective, 0.1103 m2 of floor.
     */
    private Shipment measuredReadyShipment() throws Exception {
        return readyShipmentOn(deckVehicle(), true);
    }

    private Shipment readyShipmentOn(String vehicleId, boolean measured) throws Exception {
        LocalDate date = nextDate();
        String order = order(date, "2");
        if (measured) {
            execute("UPDATE tms.transport_order_line SET geometry_status = 'MEASURED', stacks = 10,"
                    + " stack_height_m = 1.363, effective_volume_m3 = 0.1503, floor_area_m2 = 0.1103"
                    + " WHERE order_id = '" + order + "'");
            execute("UPDATE tms.transport_order SET planning_volume_m3 = 0.1503, floor_area_m2 = 0.1103,"
                    + " max_stack_height_m = 1.363, max_unit_height_m = 0.4 WHERE id = '" + order + "'");
        }
        String run = newRun(date);
        String trip = newTrip(run, date, vehicleId);
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order + "\"}"))
                .andExpect(status().isOk());
        confirm(run);
        transition(trip, "ready", null).andExpect(status().isOk());
        return new Shipment(trip, order, value("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'"),
                value("SELECT external_reference FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT v.license_plate FROM tms.trip t JOIN tms.vehicle v ON v.id = t.vehicle_id WHERE t.id = '"
                        + trip + "'"));
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

    /**
     * {@link #document} in contract v1.1 (§7.3): {@code vehicle.trailerLicensePlate} and the
     * order's geometry fields, given as a JSON fragment.
     */
    private static String documentV11(Shipment shipment, String reference, String plate, String trailer,
            String geometry) {
        return """
                {"sourceSystem":"EWM_EBIM","dispatchReference":"%s","revision":1,"transportReference":"%s",
                 "loadReference":"CRG-%s","warehouseCode":"CD01","actualDispatchAt":"%s",
                 "carrier":{"code":"TRSA","name":"Transportes SA"},
                 "vehicle":{"licensePlate":"%s","trailerLicensePlate":"%s","type":"FURGON"},
                 "totals":{"handlingUnits":2,"weightKg":1000},
                 "orders":[{"externalSource":"SAPB1_PE","externalReference":"%s","warehouseOrderNumber":"ORR-1",
                   "status":"SHIPPED","handlingUnits":2,"weightKg":1000,%s,
                   "lines":[{"lineNumber":1,"materialCode":"SKU-100","quantity":100,"uom":"UN","handlingUnitCode":"HU-1"}]}]}
                """.formatted(reference, shipment.shipmentNumber, reference, OffsetDateTime.now(), plate, trailer,
                shipment.orderReference, geometry);
    }

    private ResultActions plan(Shipment shipment) throws Exception {
        return mockMvc.perform(get(SHIPMENTS + "/" + shipment.shipmentNumber)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken))
                .andExpect(status().isOk());
    }

    private void recordDelivery(Shipment shipment, String stop, String body) throws Exception {
        mockMvc.perform(asAdmin(put(TRIPS + "/" + shipment.tripId + "/stops/" + stop + "/orders/" + shipment.orderId
                        + "/delivery")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private ResultActions stopAction(String tripId, String stopId, String action) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/stops/" + stopId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private List<String> stopIdsOf(String tripId) throws Exception {
        String body = mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.stops[*].id");
    }

    /** A webhook subscription of the company to {@code eventType}; returns its id. */
    private String subscribe(String eventType) throws Exception {
        String response = mockMvc.perform(asAdmin(post("/api/v1/webhooks")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"WMS " + SEQUENCE.incrementAndGet() + "\","
                                + "\"targetUrl\":\"http://wms.example.invalid/tms\",\"eventTypes\":[\"" + eventType + "\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.subscription.id");
    }

    /** One figure of the breakdown line for {@code component}; a JsonPath filter always reads a list. */
    private static BigDecimal componentNumber(String json, String component, String field) {
        List<Object> read = JsonPath.read(json, "$.components[?(@.component == '" + component + "')]." + field);
        assertThat(read).hasSize(1);
        return new BigDecimal(String.valueOf(read.get(0)));
    }

    private static BigDecimal number(String json, String path) {
        Object read = JsonPath.read(json, path);
        return new BigDecimal(String.valueOf(read));
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
        return newTrip(runId, date, vehicle());
    }

    private String newTrip(String runId, LocalDate date, String vehicleId) throws Exception {
        String departure = date.atTime(8, 0).atZone(LIMA).toOffsetDateTime().toString();
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + runId + "/trips"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicleId + "\",\"plannedDepartureAt\":\"" + departure
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

    /** A vehicle on a type with a measured deck (V62): 7.20 x 2.40 x 2.40 m, floor filled to 0.85. */
    private static String deckVehicle() {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets, length_m, width_m, height_m, load_floor_fill_factor) VALUES ('"
                + COMPANY + "', 'WEX-DECK-" + number + "', 'Deck', 100000, 400, 1000, 7.20, 2.40, 2.40, 0.85)");
        return vehicleOfType(type);
    }

    private static String vehicleOfType(String type) {
        int number = SEQUENCE.incrementAndGet();
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'WEX-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "D%02d-%03d", number % 100, number) + "', '" + carrier + "', '" + type + "')");
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
