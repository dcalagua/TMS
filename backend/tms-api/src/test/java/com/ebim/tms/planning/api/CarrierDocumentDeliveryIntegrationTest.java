package com.ebim.tms.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.hamcrest.Matchers;
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
 * ADR-018 end to end: a carrier user sees the trips of their carrier once the warehouse dispatch
 * arrives, reports each document (a photo mandatory for DELIVERED, idempotent on retry), and the
 * office watches it and confirms the return. Plus the boundary: another carrier, the office API and
 * the database itself (V57's policies) keep a carrier user to their own trips.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CarrierDocumentDeliveryIntegrationTest.JwtDecoderOverride.class)
class CarrierDocumentDeliveryIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";
    private static final String PORTAL = "/api/v1/portal/trips";
    private static final String MONITOR = "/api/v1/document-deliveries";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");
    private static final UUID CARRIER_A_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e2");
    private static final UUID CARRIER_B_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e3");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destination;
    private static String carrierA;
    private static String carrierB;
    private static String warehouseToken;
    private static String erpToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_carrier_documents");
        seed();
        String root = Files.createTempDirectory("tms-document-evidence").toAbsolutePath().toString();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("tms.storage.evidence.mode", () -> "LOCAL");
        registry.add("tms.storage.evidence.root", () -> root);
    }

    private static void seed() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%1$s', 'CDD-ORG', 'Carrier Docs Org');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%2$s', '%1$s', 'CDD-A', 'Carrier Docs Company', 'America/Lima');
                INSERT INTO tms.company_settings (company_id, dispatch_confirmation_mode)
                    VALUES ('%2$s', 'EXTERNAL_REQUIRED');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%3$s', 'cdd.admin@example.invalid', 'Office Admin');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%1$s', '%2$s' FROM tms.app_user WHERE email = 'cdd.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'cdd.admin@example.invalid'
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
        carrierUser(CARRIER_A_AUTH, "chofer.a@example.invalid", carrierA);
        carrierUser(CARRIER_B_AUTH, "chofer.b@example.invalid", carrierB);
        warehouseToken = client("EWM", "integration.dispatch:write");
        erpToken = client("ERP", "integration.document:write");
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
    @DisplayName("the carrier sees the trip only once the dispatch arrives, with its documents pending")
    void visibleOnDispatch() throws Exception {
        Shipment shipment = confirmedShipment(carrierA);
        documents(shipment, "INVOICE", "F001-" + SEQUENCE.incrementAndGet());
        documents(shipment, "GRE_REMITENTE", "T001-" + SEQUENCE.incrementAndGet());

        asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isNotFound());
        assertThat(value("SELECT count(*) FROM tms.document_delivery WHERE trip_id = '" + shipment.tripId + "'"))
                .isEqualTo("0");

        dispatch(shipment);

        asCarrier(CARRIER_A_AUTH, get(PORTAL)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tripId == '" + shipment.tripId + "')].documentCount").value(2))
                .andExpect(jsonPath("$[?(@.tripId == '" + shipment.tripId + "')].pendingCount").value(2))
                .andExpect(jsonPath("$[?(@.tripId == '" + shipment.tripId + "')].vehicleLicensePlate")
                        .value(shipment.plate));
        asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceEnabled").value(true))
                .andExpect(jsonPath("$.stops[0].destinationName").value("Cliente Uno"))
                .andExpect(jsonPath("$.stops[0].documents.length()").value(2))
                .andExpect(jsonPath("$.stops[0].documents[0].status").value("PENDING"))
                .andExpect(jsonPath("$.stops[0].documents[0].orderNumbers[0]").value(shipment.orderNumber))
                // A carrier is not told what an invoice is worth.
                .andExpect(jsonPath("$.stops[0].documents[0].amount").doesNotExist());

        // A document the ERP sends after the truck left is tracked too.
        documents(shipment, "DELIVERY_NOTE", "GR-" + SEQUENCE.incrementAndGet());
        asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].documents.length()").value(3));
    }

    @Test
    @DisplayName("another carrier, the office API and the database itself keep a carrier user to their own trips")
    void carrierBoundary() throws Exception {
        Shipment shipment = confirmedShipment(carrierA);
        String number = "F001-" + SEQUENCE.incrementAndGet();
        documents(shipment, "INVOICE", number);
        dispatch(shipment);

        asCarrier(CARRIER_B_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isNotFound());
        asCarrier(CARRIER_B_AUTH, get(PORTAL)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tripId == '" + shipment.tripId + "')]").isEmpty());
        // No office permission reaches a carrier membership, whatever role is attached.
        asCarrier(CARRIER_A_AUTH, get(TRIPS + "/" + shipment.tripId)).andExpect(status().isForbidden());
        asCarrier(CARRIER_A_AUTH, get(MONITOR)).andExpect(status().isForbidden());
        // ...and an office user is not a carrier user.
        asAdmin(get(PORTAL)).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TestJwts.validFor(CARRIER_A_AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].carrierId").value(carrierA))
                .andExpect(jsonPath("$.companies[0].carrierName").value("Transportes SA"))
                .andExpect(jsonPath("$.companies[0].capabilities").value(Matchers.contains("CARRIER_PORTAL")));

        // V57: as the runtime role, with carrier B's setting, carrier A's rows do not exist.
        assertThat(asRuntimeRole(carrierB, "SELECT count(*) FROM tms.trip WHERE id = '" + shipment.tripId + "'"))
                .isEqualTo("0");
        assertThat(asRuntimeRole(carrierB, "SELECT count(*) FROM tms.document_delivery WHERE trip_id = '"
                + shipment.tripId + "'")).isEqualTo("0");
        assertThat(asRuntimeRole(carrierB, "SELECT count(*) FROM tms.logistics_document WHERE document_number = '"
                + number + "'")).isEqualTo("0");
        assertThat(asRuntimeRole(carrierB, "SELECT count(*) FROM tms.transport_order WHERE id = '"
                + shipment.orderId + "'")).isEqualTo("0");
        assertThat(asRuntimeRole(carrierA, "SELECT count(*) FROM tms.document_delivery WHERE trip_id = '"
                + shipment.tripId + "'")).isEqualTo("1");
        // And with no carrier setting - every office request - nothing is narrowed.
        assertThat(asRuntimeRole(null, "SELECT count(*) FROM tms.document_delivery WHERE trip_id = '"
                + shipment.tripId + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("DELIVERED needs a photo; a retry with the same key records nothing twice; the key cannot be reused elsewhere")
    void reportRulesAndIdempotency() throws Exception {
        Shipment shipment = confirmedShipment(carrierA);
        documents(shipment, "INVOICE", "F001-" + SEQUENCE.incrementAndGet());
        documents(shipment, "GRE_REMITENTE", "T001-" + SEQUENCE.incrementAndGet());
        dispatch(shipment);
        List<String> documentIds = documentIds(shipment);
        String key = UUID.randomUUID().toString();

        report(shipment, documentIds.get(0), key, "DELIVERED", null, false).andExpect(status().isBadRequest());
        report(shipment, documentIds.get(0), null, "DELIVERED", null, true).andExpect(status().isBadRequest());
        report(shipment, documentIds.get(1), UUID.randomUUID().toString(), "REJECTED", null, false)
                .andExpect(status().isBadRequest());

        report(shipment, documentIds.get(0), key, "DELIVERED", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.evidence.length()").value(2));
        report(shipment, documentIds.get(0), key, "DELIVERED", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        assertThat(value("SELECT count(*) FROM tms.document_delivery_event e JOIN tms.document_delivery d"
                + " ON d.id = e.document_delivery_id WHERE d.trip_id = '" + shipment.tripId + "'")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM tms.document_delivery_evidence e JOIN tms.document_delivery d"
                + " ON d.id = e.document_delivery_id WHERE d.trip_id = '" + shipment.tripId + "'")).isEqualTo("2");
        report(shipment, documentIds.get(1), key, "REJECTED", "CUSTOMER_REFUSED", false)
                .andExpect(status().isConflict());

        report(shipment, documentIds.get(1), UUID.randomUUID().toString(), "REJECTED", "CUSTOMER_REFUSED", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("CUSTOMER_REFUSED"));

        // The photo comes back to its carrier, through the API only.
        String detail = asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String evidenceId = JsonPath.read(detail, "$.stops[0].documents[?(@.documentId == '" + documentIds.get(0)
                + "')].evidence[0].id").toString().replaceAll("[\\[\\]\"]", "");
        asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId + "/documents/" + documentIds.get(0)
                + "/evidence/" + evidenceId + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("photo-bytes".getBytes()));
        asCarrier(CARRIER_B_AUTH, get(PORTAL + "/" + shipment.tripId + "/documents/" + documentIds.get(0)
                + "/evidence/" + evidenceId + "/content"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the office monitors, flags a disagreement with the goods, and closes the document on return")
    void officeMonitorAndReturn() throws Exception {
        Shipment shipment = confirmedShipment(carrierA);
        String number = "F009-" + SEQUENCE.incrementAndGet();
        documents(shipment, "INVOICE", number);
        dispatch(shipment);
        String documentId = documentIds(shipment).get(0);
        report(shipment, documentId, UUID.randomUUID().toString(), "DELIVERED", null, true).andExpect(status().isOk());

        // The goods, though, failed at the stop (ADR-009): the monitor says so and resolves nothing.
        execute("INSERT INTO tms.order_delivery (company_id, trip_id, trip_stop_id, order_id, result, source,"
                + " notes, actor_machine_label) SELECT '" + COMPANY + "', '" + shipment.tripId + "', s.id, '"
                + shipment.orderId + "', 'FAILED', 'SYSTEM', 'Cliente cerrado', 'test' FROM tms.trip_stop s"
                + " WHERE s.trip_id = '" + shipment.tripId + "'");

        String today = LocalDate.now(LIMA).toString();
        String page = asAdmin(get(MONITOR).param("q", number).param("dateFrom", today).param("dateTo", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].carrierName").value("Transportes SA"))
                .andExpect(jsonPath("$.content[0].recordedByName").value("chofer.a@example.invalid"))
                .andExpect(jsonPath("$.content[0].evidenceCount").value(2))
                .andExpect(jsonPath("$.content[0].inconsistency").value("DOCUMENT_DELIVERED_GOODS_FAILED"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(page, "$.content[0].id");
        asAdmin(get(MONITOR + "/summary").param("q", number)).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(1))
                .andExpect(jsonPath("$.awaitingReturn").value(1))
                .andExpect(jsonPath("$.inconsistent").value(1))
                .andExpect(jsonPath("$.byCarrier[0].carrierName").value("Transportes SA"));

        asAdmin(post(MONITOR + "/" + id + "/return").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"Cargo sellado recibido\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.returnedAt").isNotEmpty())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[1].eventType").value("RETURNED"))
                .andExpect(jsonPath("$.evidence.length()").value(2));
        asAdmin(post(MONITOR + "/" + id + "/return").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        // Closed: the carrier can no longer change it.
        report(shipment, documentId, UUID.randomUUID().toString(), "NOT_DELIVERED", "CUSTOMER_ABSENT", false)
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ADR-019: the tracking report lists what the warehouse dispatched, with stops and documents")
    void shipmentTrackingReport() throws Exception {
        Shipment shipment = confirmedShipment(carrierA);
        documents(shipment, "INVOICE", "F010-" + SEQUENCE.incrementAndGet());
        documents(shipment, "GRE_REMITENTE", "T010-" + SEQUENCE.incrementAndGet());
        Shipment notDispatched = confirmedShipment(carrierB);
        dispatch(shipment);
        String documentId = documentIds(shipment).get(0);
        report(shipment, documentId, UUID.randomUUID().toString(), "DELIVERED", null, true).andExpect(status().isOk());

        asAdmin(get("/api/v1/monitoring/shipment-tracking").param("q", shipment.shipmentNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].tripId").value(shipment.tripId))
                .andExpect(jsonPath("$.content[0].carrierName").value("Transportes SA"))
                .andExpect(jsonPath("$.content[0].vehicleLicensePlate").value(shipment.plate))
                .andExpect(jsonPath("$.content[0].dispatchReference").isNotEmpty())
                .andExpect(jsonPath("$.content[0].originName").value("CD Lima"))
                .andExpect(jsonPath("$.content[0].documentCount").value(2))
                .andExpect(jsonPath("$.content[0].deliveredCount").value(1))
                .andExpect(jsonPath("$.content[0].pendingCount").value(1))
                .andExpect(jsonPath("$.content[0].stops[0].destinationName").value("Cliente Uno"))
                .andExpect(jsonPath("$.content[0].stops[0].documentCount").value(2))
                .andExpect(jsonPath("$.content[0].stops[0].pendingCount").value(1));
        // Confirmed but not dispatched: not an actual shipment yet.
        asAdmin(get("/api/v1/monitoring/shipment-tracking").param("q", notDispatched.shipmentNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        asAdmin(get("/api/v1/monitoring/shipment-tracking").param("q", shipment.shipmentNumber)
                        .param("documents", "COMPLETE"))
                .andExpect(jsonPath("$.totalElements").value(0));
        asAdmin(get("/api/v1/monitoring/shipment-tracking/summary").param("q", shipment.shipmentNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipments").value(1))
                .andExpect(jsonPath("$.withPendingDocuments").value(1))
                .andExpect(jsonPath("$.documentsDelivered").value(1));
        // A carrier user has no office report.
        asCarrier(CARRIER_A_AUTH, get("/api/v1/monitoring/shipment-tracking")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an administrator invites a carrier user with carrier roles only")
    void inviteCarrierUser() throws Exception {
        asAdmin(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mixed@example.invalid\",\"fullName\":\"Mixed\",\"roleCodes\":[\"PLANNER\"],"
                                + "\"carrierId\":\"" + carrierA + "\"}"))
                .andExpect(status().isBadRequest());
        asAdmin(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"office@example.invalid\",\"fullName\":\"Office\","
                                + "\"roleCodes\":[\"CARRIER_OPERATOR\"]}"))
                .andExpect(status().isBadRequest());
        asAdmin(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"despacho@example.invalid\",\"fullName\":\"Despacho TRSA\","
                                + "\"roleCodes\":[\"CARRIER_OPERATOR\"],\"carrierId\":\"" + carrierA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.carrierId").value(carrierA))
                .andExpect(jsonPath("$.carrierName").value("Transportes SA"));
    }

    // -----------------------------------------------------------------------------------------

    private record Shipment(String tripId, String orderId, String orderNumber, String shipmentNumber,
            String orderReference, String plate) {
    }

    private Shipment confirmedShipment(String carrier) throws Exception {
        LocalDate date = LocalDate.now(LIMA).minusDays(30).plusDays(SEQUENCE.incrementAndGet() % 25);
        String order = order(date);
        String run = newRun(date);
        String trip = newTrip(run, date, carrier);
        asAdmin(post(TRIPS + "/" + trip + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\"}")).andExpect(status().isOk());
        asAdmin(post(PLANNING + "/runs/" + run + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        return new Shipment(trip, order, value("SELECT order_number FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'"),
                value("SELECT external_reference FROM tms.transport_order WHERE id = '" + order + "'"),
                value("SELECT v.license_plate FROM tms.trip t JOIN tms.vehicle v ON v.id = t.vehicle_id WHERE t.id = '"
                        + trip + "'"));
    }

    private void dispatch(Shipment shipment) throws Exception {
        String reference = "SLS-" + SEQUENCE.incrementAndGet();
        String body = """
                {"sourceSystem":"EWM_EBIM","dispatchReference":"%s","revision":1,"transportReference":"%s",
                 "loadReference":"CRG-%s","warehouseCode":"CD01","actualDispatchAt":"%s",
                 "carrier":{"code":"TRSA","name":"Transportes SA"},"vehicle":{"licensePlate":"%s","type":"FURGON"},
                 "totals":{"handlingUnits":2,"weightKg":1000},
                 "orders":[{"externalSource":"SAPB1_PE","externalReference":"%s","warehouseOrderNumber":"ORR-1",
                   "status":"SHIPPED","handlingUnits":2,"weightKg":1000,
                   "lines":[{"lineNumber":1,"materialCode":"SKU-100","quantity":100,"uom":"UN","handlingUnitCode":"HU-1"}]}],
                 "document":{"format":"SLS","version":"LOGFIRE-1","contentType":"text/plain","content":"[H1]|CD01"}}
                """.formatted(reference, shipment.shipmentNumber, reference, OffsetDateTime.now(), shipment.plate,
                shipment.orderReference);
        mockMvc.perform(post("/integration/v1/dispatch-confirmations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void documents(Shipment shipment, String type, String number) throws Exception {
        mockMvc.perform(post("/integration/v1/logistics-documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + erpToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSystem\":\"SAPB1_PE\",\"documentType\":\"" + type + "\",\"documentNumber\":\""
                                + number + "\",\"issueDate\":\"2026-10-01\",\"amount\":1500.50,\"currency\":\"PEN\","
                                + "\"orders\":[{\"externalSource\":\"SAPB1_PE\",\"externalReference\":\""
                                + shipment.orderReference + "\"}]}"))
                .andExpect(status().isCreated());
    }

    private List<String> documentIds(Shipment shipment) throws Exception {
        String detail = asCarrier(CARRIER_A_AUTH, get(PORTAL + "/" + shipment.tripId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(detail, "$.stops[0].documents[*].documentId");
    }

    private ResultActions report(Shipment shipment, String documentId, String key, String outcome, String reason,
            boolean withPhoto) throws Exception {
        MockMultipartHttpServletRequestBuilder request =
                multipart(PORTAL + "/" + shipment.tripId + "/documents/" + documentId + "/report");
        request.param("status", outcome);
        if (reason != null) {
            request.param("reasonCode", reason);
        }
        if (withPhoto) {
            request.file(new MockMultipartFile("photos", "foto.jpg", "image/jpeg", "photo-bytes".getBytes()));
            request.file(new MockMultipartFile("signature", "firma.png", "image/png", "signature-bytes".getBytes()));
        }
        if (key != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, key);
        }
        return asCarrier(CARRIER_A_AUTH, request);
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
                + " customer_name, external_source, external_reference) VALUES ('" + COMPANY + "', 'TO-CDD-"
                + String.format(Locale.ROOT, "%06d", number) + "', '" + origin + "', '" + destination + "', '"
                + serviceDate + "', 'READY_FOR_PLANNING', 1000, 10, 10, 'Cliente Uno SAC', 'SAPB1_PE', 'PED-CDD-"
                + number + "')");
        execute("INSERT INTO tms.transport_order_line (order_id, line_number, material_code, material_description,"
                + " quantity, uom) VALUES ('" + id + "', 1, 'SKU-100', 'Cemento', 100, 'UN')");
        return id;
    }

    private static String vehicle(String carrier) {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'CDD-TYPE-" + number
                + "', 'Type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'CDD-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "C%02d-%03d", number % 100, number) + "', '" + carrier + "', '" + type
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
                statement.execute("SELECT set_config('tms.company_id', '" + COMPANY + "', true), set_config("
                        + "'tms.carrier_id', '" + (carrier == null ? "" : carrier) + "', true)");
                statement.execute("SET LOCAL ROLE tms_app");
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
