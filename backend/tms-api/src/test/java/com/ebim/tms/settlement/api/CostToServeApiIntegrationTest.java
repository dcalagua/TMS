package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.settlement.application.CostAllocationSweep;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cost to serve end to end (migration V68, ADR-026, phase 70).
 *
 * <p>A trip's cost goes to its orders by the company's basis - first on the estimate, again when the
 * cost is closed, again when the carrier's invoice is approved - and every allocation is a version
 * that the next one supersedes without rewriting. An order without a measure receives nothing and is
 * counted. The report groups the current versions by customer, destination and route, with cost per
 * kg, m3 and order and no margin; the KPI report gains its finance block. The rows cannot be edited
 * by the runtime role or the owner, and another company sees none of it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CostToServeApiIntegrationTest.JwtDecoderOverride.class)
class CostToServeApiIntegrationTest {

    private static final String REPORT = "/api/v1/settlement/cost-to-serve";
    private static final String TRIPS = "/api/v1/settlement/cost-allocation/trips/";
    private static final String INVOICES = "/api/v1/settlement/invoices";

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000701");
    private static final UUID COMPANY_A = UUID.fromString("99999999-0000-4000-8000-0000000007c1");
    /** Splits by stops; only the stops test writes here. */
    private static final UUID COMPANY_B = UUID.fromString("99999999-0000-4000-8000-0000000007c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000007e1");
    private static final UUID PLANNER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000007e2");
    private static final UUID VIEWER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000007e3");
    private static final UUID CHECKER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000007e4");

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String adminUserId;
    private static String carrierA;
    private static String carrierB;
    private static String cardA;
    private static String cardB;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CostAllocationSweep sweep;

    private String adminToken;
    private String plannerToken;
    private String viewerToken;
    private String checkerToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_cost_to_serve_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'CTS-ORG', 'Cost Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'CTS-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'CTS-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'cts.admin@example.invalid', 'CTS Admin'), ('" + PLANNER_AUTH
                + "', 'cts.planner@example.invalid', 'CTS Planner'), ('" + VIEWER_AUTH
                + "', 'cts.viewer@example.invalid', 'CTS Viewer'), ('" + CHECKER_AUTH
                + "', 'cts.checker@example.invalid', 'CTS Checker')");
        adminUserId = idOf("SELECT id FROM tms.app_user WHERE email = 'cts.admin@example.invalid'");
        membership("cts.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("cts.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("cts.planner@example.invalid", COMPANY_A, "PLANNER");
        membership("cts.viewer@example.invalid", COMPANY_A, "VIEWER");
        membership("cts.checker@example.invalid", COMPANY_A, "COMPANY_ADMIN");

        carrierA = carrier(COMPANY_A, "CTS-CA");
        carrierB = carrier(COMPANY_B, "CTS-CB");
        cardA = rateCard(COMPANY_A, "RC-CTS-A", carrierA);
        cardB = rateCard(COMPANY_B, "RC-CTS-B", carrierB);
        execute("INSERT INTO tms.tolerance_policy (company_id, percentage) VALUES ('" + COMPANY_A + "', 60)");
        execute("INSERT INTO tms.company_settings (company_id, cost_allocation_basis) VALUES ('" + COMPANY_B
                + "', 'STOPS')");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
        checkerToken = TestJwts.validFor(CHECKER_AUTH);
    }

    // ------------------------------------------------------------------ versions

    @Test
    @DisplayName("estimate -> closed actual -> approved invoice: three versions, each superseding, none rewritten")
    void versionsFollowTheCost() throws Exception {
        String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "1000.00", OffsetDateTime.now().minusHours(6));
        String store1 = location(COMPANY_A, "Store North");
        String store2 = location(COMPANY_A, "Store South");
        String o1 = order(COMPANY_A, store1, "CUST-1", null, "300", "1.5");
        String o2 = order(COMPANY_A, store2, null, "Bodega Dos", "700", "2.5");
        String o3 = order(COMPANY_A, store1, "CUST-1", null, "0", "1.0");
        assign(COMPANY_A, trip, o1, "300", "1.5");
        assign(COMPANY_A, trip, o2, "700", "2.5");
        assign(COMPANY_A, trip, o3, "0", "1.0");

        sweep.sweepAll();
        assertThat(scalar(runOf(trip, 1, "basis || '/' || cost_source || '/' || cost_amount || '/' || trigger"
                + " || '/' || unallocated_order_count || '/' || unallocated_reason")))
                .isEqualTo("WEIGHT/ESTIMATED/1000.00/TRIP_EXECUTED/1/NO_BASIS");
        assertThat(amountOf(trip, 1, o1)).isEqualTo("300.00");
        assertThat(amountOf(trip, 1, o2)).isEqualTo("700.00");
        // No invented allocation: the unweighed order received nothing and says why.
        assertThat(scalar("SELECT coalesce(a.allocated_amount::text, 'null') || '/' || a.reason FROM"
                + " tms.trip_cost_allocation a JOIN tms.trip_cost_allocation_run r ON r.id = a.run_id"
                + " WHERE r.trip_id = '" + trip + "' AND r.version = 1 AND a.order_id = '" + o3 + "'"))
                .isEqualTo("null/NO_BASIS");
        // The sweep is a machine, and the audit trail says so.
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + trip
                + "' AND action = 'COST_ALLOCATED' AND actor_machine_label = 'system:cost-allocation-sweep'"))
                .isEqualTo(1);

        // Twice: nothing more.
        sweep.sweepAll();
        assertThat(runs(trip)).isEqualTo(1);

        closeCost(trip, "1200.00");
        sweep.sweepAll();
        sweep.sweepAll();
        assertThat(runs(trip)).isEqualTo(2);
        assertThat(scalar(runOf(trip, 2, "cost_source || '/' || cost_amount || '/' || trigger")))
                .isEqualTo("ACTUAL/1200.00/TRIP_COST_CLOSED");
        assertThat(amountOf(trip, 2, o1)).isEqualTo("360.00");
        assertThat(amountOf(trip, 2, o2)).isEqualTo("840.00");
        // Version 1 is still there, as it was, and no longer current.
        assertThat(amountOf(trip, 1, o1)).isEqualTo("300.00");
        assertThat(scalar(runOf(trip, 1, "(superseded_at IS NOT NULL)::text"))).isEqualTo("true");

        String invoice = invoice(COMPANY_A, carrierA, trip, null, "1100.00");
        approve(invoice, COMPANY_A);
        assertThat(runs(trip)).isEqualTo(3);
        assertThat(scalar(runOf(trip, 3, "cost_source || '/' || cost_amount || '/' || trigger || '/' || computed_by")))
                .isEqualTo("INVOICED/1100.00/INVOICE_APPROVED/"
                        + idOf("SELECT id FROM tms.app_user WHERE email = 'cts.checker@example.invalid'"));
        assertThat(amountOf(trip, 3, o1)).isEqualTo("330.00");
        assertThat(amountOf(trip, 3, o2)).isEqualTo("770.00");
        // A closed actual after the invoice does not move it: the carrier billed the trip.
        sweep.sweepAll();
        assertThat(runs(trip)).isEqualTo(3);

        mockMvc.perform(asViewer(get(TRIPS + trip), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(3))
                .andExpect(jsonPath("$.current.costSource").value("INVOICED"))
                .andExpect(jsonPath("$.current.unallocatedOrderCount").value(1))
                .andExpect(jsonPath("$.current.lines.length()").value(3))
                .andExpect(jsonPath("$.versions.length()").value(3))
                .andExpect(jsonPath("$.versions[0].version").value(3))
                .andExpect(jsonPath("$.versions[2].version").value(1))
                .andExpect(jsonPath("$.versions[2].lines").isEmpty());
        mockMvc.perform(asViewer(get(TRIPS + trip + "/versions/1"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costSource").value("ESTIMATED"))
                .andExpect(jsonPath("$.lines[?(@.orderId == '" + o1 + "')].allocatedAmount").value(300.00));
    }

    @Test
    @DisplayName("Recalcular is an administrator's, always writes a version, and follows the company's basis")
    void manualRecalculation() throws Exception {
        String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "900.00", OffsetDateTime.now().minusHours(5));
        String store = location(COMPANY_A, "Store Manual");
        String o1 = order(COMPANY_A, store, "CUST-M", null, "100", "1");
        String o2 = order(COMPANY_A, store, "CUST-M", null, "800", "8");
        assign(COMPANY_A, trip, o1, "100", "1");
        assign(COMPANY_A, trip, o2, "800", "8");

        mockMvc.perform(asPlanner(post(TRIPS + trip + "/recalculate"), COMPANY_A)).andExpect(status().isForbidden());
        mockMvc.perform(asViewer(post(TRIPS + trip + "/recalculate"), COMPANY_A)).andExpect(status().isForbidden());

        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(1))
                .andExpect(jsonPath("$.current.trigger").value("MANUAL"));
        // The same figures again: still a version, because a person asked.
        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2));
        assertThat(scalar(runOf(trip, 2, "computed_by::text"))).isEqualTo(adminUserId);
        assertThat(amountOf(trip, 2, o1)).isEqualTo("100.00");

        // A trip with no figure at all cannot be allocated, and says so.
        String bare = dispatchedTrip(COMPANY_A, carrierA, cardA, null, OffsetDateTime.now().minusHours(5));
        mockMvc.perform(asAdmin(post(TRIPS + bare + "/recalculate"), COMPANY_A)).andExpect(status().isConflict());
        assertThat(runs(bare)).isZero();
    }

    @Test
    @DisplayName("stops: equal parts per stop, then by weight; the basis travels with the run")
    void stopsBasis() throws Exception {
        String trip = dispatchedTrip(COMPANY_B, carrierB, cardB, "1000.00", OffsetDateTime.now().minusHours(4));
        String s1 = location(COMPANY_B, "Stop One");
        String s2 = location(COMPANY_B, "Stop Two");
        String a = order(COMPANY_B, s1, "B-1", null, "100", "1");
        String b = order(COMPANY_B, s1, "B-1", null, "300", "1");
        String c = order(COMPANY_B, s2, "B-2", null, "5", "1");
        assign(COMPANY_B, trip, a, "100", "1");
        assign(COMPANY_B, trip, b, "300", "1");
        assign(COMPANY_B, trip, c, "5", "1");

        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.basis").value("STOPS"));
        assertThat(amountOf(trip, 1, a)).isEqualTo("125.00");
        assertThat(amountOf(trip, 1, b)).isEqualTo("375.00");
        assertThat(amountOf(trip, 1, c)).isEqualTo("500.00");
    }

    // ------------------------------------------------------------------ report

    @Test
    @DisplayName("the report groups by customer, destination and route; per kg, m3 and order; no margin; CSV")
    void report() throws Exception {
        OffsetDateTime departed = OffsetDateTime.now().minusHours(3);
        String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "600.00", departed);
        String store = location(COMPANY_A, "Store Report");
        String o1 = order(COMPANY_A, store, "REPORT-CUST", null, "200", "2");
        String o2 = order(COMPANY_A, store, "REPORT-CUST", null, "100", "1");
        String o3 = order(COMPANY_A, store, "REPORT-CUST", null, "0", "1");
        assign(COMPANY_A, trip, o1, "200", "2");
        assign(COMPANY_A, trip, o2, "100", "1");
        assign(COMPANY_A, trip, o3, "0", "1");
        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_A)).andExpect(status().isOk());

        LocalDate day = departed.atZoneSameInstant(LIMA).toLocalDate();
        String range = "?from=" + day.minusDays(1) + "&to=" + day.plusDays(1);
        String customer = "$.groups[?(@.label == 'REPORT-CUST')]";
        mockMvc.perform(asViewer(get(REPORT + range + "&groupBy=CUSTOMER"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginAvailable").value(false))
                .andExpect(jsonPath("$.marginNote").value(org.hamcrest.Matchers.containsString("No margin")))
                .andExpect(jsonPath("$.basis").value("WEIGHT"))
                .andExpect(jsonPath(customer + ".cost").value(600.00))
                .andExpect(jsonPath(customer + ".orders").value(2))
                .andExpect(jsonPath(customer + ".unallocatedOrders").value(1))
                .andExpect(jsonPath(customer + ".costPerKg").value(2.0))
                .andExpect(jsonPath(customer + ".costPerM3").value(200.0))
                .andExpect(jsonPath(customer + ".costPerOrder").value(300.0));
        mockMvc.perform(asViewer(get(REPORT + range + "&groupBy=DESTINATION"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[?(@.key == '" + store + "')].cost").value(600.00));
        mockMvc.perform(asViewer(get(REPORT + range + "&groupBy=ROUTE"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("ROUTE"));
        mockMvc.perform(asViewer(get(REPORT + "/export" + range + "&groupBy=CUSTOMER&format=CSV"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("group_by,key,label,currency")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REPORT-CUST")));
        mockMvc.perform(asViewer(get(REPORT + "/export" + range + "&format=XML"), COMPANY_A))
                .andExpect(status().isBadRequest());

        // The finance block of the KPI report: cost per kg from the same allocations.
        mockMvc.perform(asAdmin(get("/api/v1/reporting/kpis" + range), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finance.byCurrency[?(@.currency == 'PEN')].costPerKg").exists());

        // Another company sees none of it.
        mockMvc.perform(asAdmin(get(REPORT + range), COMPANY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath(customer).isEmpty());
        mockMvc.perform(asAdmin(get(TRIPS + trip), COMPANY_B)).andExpect(status().isNotFound());
        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_B)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ immutability

    @Test
    @DisplayName("rows are append-only and a run only ever gets superseded, once - by grant and by trigger")
    void appendOnly() throws Exception {
        String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "500.00", OffsetDateTime.now().minusHours(2));
        String o1 = order(COMPANY_A, location(COMPANY_A, "Store Immutable"), "IMM", null, "10", "1");
        assign(COMPANY_A, trip, o1, "10", "1");
        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_A)).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post(TRIPS + trip + "/recalculate"), COMPANY_A)).andExpect(status().isOk());
        String first = idOf(runOf(trip, 1, "id"));
        String second = idOf(runOf(trip, 2, "id"));

        // The owner.
        assertThatThrownBy(() -> executeStrict("UPDATE tms.trip_cost_allocation SET allocated_amount = 1"
                + " WHERE run_id = '" + second + "'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeStrict("DELETE FROM tms.trip_cost_allocation WHERE run_id = '" + second
                + "'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeStrict("UPDATE tms.trip_cost_allocation_run SET cost_amount = 1"
                + " WHERE id = '" + second + "'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeStrict("UPDATE tms.trip_cost_allocation_run SET superseded_at = now()"
                + " WHERE id = '" + first + "'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeStrict("DELETE FROM tms.trip_cost_allocation_run WHERE id = '" + first
                + "'")).hasMessageContaining("append-only");

        // The runtime role.
        String asRuntime = "SET ROLE tms_app; SELECT set_config('tms.company_id', '" + COMPANY_A + "', false); ";
        assertThatThrownBy(() -> executeStrict(asRuntime + "UPDATE tms.trip_cost_allocation SET reason = 'NO_BASIS'"
                + " WHERE run_id = '" + second + "'")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeStrict(asRuntime + "UPDATE tms.trip_cost_allocation_run SET version = 9"
                + " WHERE id = '" + second + "'")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeStrict(asRuntime + "DELETE FROM tms.trip_cost_allocation_run WHERE id = '"
                + second + "'")).hasMessageContaining("permission denied");
        // Two current runs for one trip are impossible.
        assertThatThrownBy(() -> executeStrict("INSERT INTO tms.trip_cost_allocation_run (company_id, trip_id,"
                + " version, basis, cost_source, currency, cost_amount, allocated_amount, order_count,"
                + " unallocated_order_count, service_date, trigger) VALUES ('" + COMPANY_A + "', '" + trip
                + "', 3, 'WEIGHT', 'ESTIMATED', 'PEN', 1, 1, 1, 0, '2026-10-18', 'MANUAL')"))
                .hasMessageContaining("uq_trip_cost_allocation_run_current");
        assertThat(runs(trip)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private static String runOf(String trip, int version, String expression) {
        return "SELECT " + expression + " FROM tms.trip_cost_allocation_run WHERE trip_id = '" + trip
                + "' AND version = " + version;
    }

    private static String amountOf(String trip, int version, String order) {
        return scalar("SELECT a.allocated_amount::text FROM tms.trip_cost_allocation a"
                + " JOIN tms.trip_cost_allocation_run r ON r.id = a.run_id WHERE r.trip_id = '" + trip
                + "' AND r.version = " + version + " AND a.order_id = '" + order + "'");
    }

    private static long runs(String trip) {
        return count("SELECT count(*) FROM tms.trip_cost_allocation_run WHERE trip_id = '" + trip + "'");
    }

    private static String location(UUID companyId, String name) {
        int n = SEQUENCE.incrementAndGet();
        return idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', 'L-" + n
                + "', '" + name + "') RETURNING id");
    }

    private static String order(UUID companyId, String destination, String customerReference, String customerName,
            String weight, String volume) {
        int n = SEQUENCE.incrementAndGet();
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', 'OO-" + n
                + "', 'Origin') RETURNING id");
        return idOf("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, status, customer_reference, customer_name, total_weight_kg, total_volume_m3,"
                + " total_pallets) VALUES ('" + companyId + "', 'TO-CTS-" + n + "', '" + origin + "', '"
                + destination + "', '2026-10-18', 'READY_FOR_PLANNING', "
                + (customerReference == null ? "NULL" : "'" + customerReference + "'") + ", "
                + (customerName == null ? "NULL" : "'" + customerName + "'") + ", " + weight + ", " + volume
                + ", 1) RETURNING id");
    }

    private static void assign(UUID companyId, String trip, String order, String weight, String volume) {
        execute("INSERT INTO tms.trip_order_assignment (company_id, trip_id, order_id, whole_order,"
                + " assigned_weight_kg, assigned_volume_m3, assigned_pallets) VALUES ('" + companyId + "', '"
                + trip + "', '" + order + "', true, " + weight + ", " + volume + ", 1)");
    }

    /** Receives an invoice billing one trip, as the admin; returns its id. */
    private String invoice(UUID companyId, String carrierId, String trip, String chargeCode, String amount)
            throws Exception {
        String line = "{\"tripId\":\"" + trip + "\",\"description\":\"Line\""
                + (chargeCode == null ? "" : ",\"chargeCode\":\"" + chargeCode + "\"")
                + ",\"lineAmount\":" + amount + "}";
        String body = "{\"carrierId\":\"" + carrierId + "\",\"invoiceNumber\":\"CTS-" + SEQUENCE.incrementAndGet()
                + "\",\"invoiceDate\":\"2026-10-05\",\"currency\":\"PEN\",\"totalAmount\":" + amount
                // V66: a PEN document states its breakdown; IGV-free here so the figures stay as before.
                + ",\"subtotalAmount\":" + amount + ",\"igvRate\":0,\"igvAmount\":0"
                + ",\"lines\":[" + line + "]}";
        String response = mockMvc.perform(asAdmin(post(INVOICES), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** Matches, works any difference as accepted, and has the checker approve. */
    private void approve(String invoiceId, UUID companyId) throws Exception {
        String matched = mockMvc.perform(asAdmin(post(INVOICES + "/" + invoiceId + "/match"), companyId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        if ("DISCREPANCY".equals(JsonPath.read(matched, "$.status"))) {
            mockMvc.perform(asAdmin(post(INVOICES + "/" + invoiceId + "/review"), companyId))
                    .andExpect(status().isOk());
            List<String> open = JsonPath.read(matched, "$.discrepancies[?(@.status == 'OPEN')].id");
            for (String discrepancy : open) {
                mockMvc.perform(asAdmin(post(INVOICES + "/" + invoiceId + "/discrepancies/" + discrepancy
                                + "/resolve"), companyId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"ACCEPTED\",\"notes\":\"Agreed with the carrier\"}"))
                        .andExpect(status().isOk());
            }
        }
        mockMvc.perform(asChecker(post(INVOICES + "/" + invoiceId + "/approve"), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }



    private static void closeCost(String trip, String actual) {
        execute("UPDATE tms.trip_cost SET actual_amount = " + actual + ", actual_reference = 'INV',"
                + " actual_recorded_at = now(), actual_recorded_by = '" + adminUserId + "', closed_at = now(),"
                + " closed_by = '" + adminUserId + "' WHERE trip_id = '" + trip + "'");
    }

    private static String rateCard(UUID companyId, String code, String carrierId) {
        return idOf("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency,"
                + " base_amount, valid_from) VALUES ('" + companyId + "', '" + code + "', 'Card " + code
                + "', '" + carrierId + "', 'CARRIER', 'PEN', 100.00, '2026-01-01') RETURNING id");
    }

    private static String carrier(UUID companyId, String code) {
        return idOf("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " SA', 'RUC', '20"
                + String.format("%09d", Math.abs(code.hashCode()) % 1_000_000_000) + "') RETURNING id");
    }

    /**
     * A trip that has left: IN_TRANSIT with everything V25/V52 require of one, on the carrier's
     * vehicle, with an estimate when {@code expected} is given.
     */
    private static String dispatchedTrip(UUID companyId, String carrierId, String cardId, String expected,
            OffsetDateTime departedAt) {
        int n = SEQUENCE.incrementAndGet();
        String vehicle = vehicle(companyId, n, carrierId);
        String trip = executedTrip(companyId, n, vehicle, carrierId, departedAt);
        if (expected != null) {
            execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, estimated_amount,"
                    + " estimated_at, rate_card_id, rate_card_code, rate_card_scope) VALUES ('" + companyId
                    + "', '" + trip + "', '" + departedAt.toLocalDate() + "', 'PEN', " + expected + ", now(), '"
                    + cardId + "', 'RC', 'CARRIER')");
        }
        return trip;
    }


    private static String vehicle(UUID companyId, int n, String carrierId) {
        String vehicleType = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + n + "', 'Rigid', 10000, 40, 20)"
                + " RETURNING id");
        return idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate, carrier_id)"
                + " VALUES ('" + companyId + "', '" + vehicleType + "', 'V-" + n + "', 'CTS-" + n + "', "
                + (carrierId == null ? "NULL" : "'" + carrierId + "'") + ") RETURNING id");
    }

    private static String executedTrip(UUID companyId, int n, String vehicle, String carrierId,
            OffsetDateTime departedAt) {
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', 'O-" + n + "', 'Origin') RETURNING id");
        String date = departedAt.toLocalDate().toString();
        String run = idOf("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date,"
                + " status) VALUES ('" + companyId + "', 'PL-CTS-" + n + "', '" + origin + "', '" + date
                + "', 'DRAFT') RETURNING id");
        String departed = departedAt.toString();
        String before = departedAt.minusHours(1).toString();
        return idOf("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number, carrier_id,"
                + " vehicle_id, status, planned_departure_at, confirmed_at, actual_departure_at,"
                + " snapshot_max_weight_kg, snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at,"
                + " confirmed_by, dispatched_by, ready_at, ready_by, dispatch_source) VALUES ('" + companyId
                + "', '" + run + "', '" + date + "', 1, " + (carrierId == null ? "NULL" : "'" + carrierId + "'")
                + ", '" + vehicle + "', 'IN_TRANSIT', '" + departed + "', '" + before + "', '" + departed
                + "', 10000, 40, 20, '" + before + "', '" + adminUserId + "', '" + adminUserId + "', '" + before
                + "', '" + adminUserId + "', 'OPERATOR') RETURNING id");
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("INSERT INTO tms.membership (app_user_id, organization_id, company_id)"
                + " SELECT id, '" + ORGANIZATION + "', '" + companyId + "' FROM tms.app_user WHERE email = '"
                + email + "'");
        execute("INSERT INTO tms.membership_role (membership_id, role_id) SELECT m.id, r.id"
                + " FROM tms.membership m JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '"
                + email + "' JOIN tms.role r ON r.code = '" + roleCode + "' WHERE m.company_id = '"
                + companyId + "'");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asPlanner(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + plannerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asChecker(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + checkerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String sql) {
        return scalar(sql);
    }

    private static String scalar(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("no row for: " + sql);
            }
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the cost-to-serve fixture", failed);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(scalar(sql));
    }

    private static void execute(String sql) {
        try {
            executeStrict(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the cost-to-serve fixture", failed);
        }
    }

    private static void executeStrict(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
