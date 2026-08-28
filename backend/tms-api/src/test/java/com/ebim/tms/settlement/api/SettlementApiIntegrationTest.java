package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Freight audit end to end (migration V46).
 *
 * <p>Four things are proven here that no unit test can: that the workflow actually runs through the
 * API, that <b>another company cannot touch any of it</b>, that <b>approving is a separate authority
 * from working the queue</b>, and that <b>two simultaneous exports produce one obligation</b>.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SettlementApiIntegrationTest.JwtDecoderOverride.class)
class SettlementApiIntegrationTest {

    private static final String INVOICES = "/api/v1/settlement/invoices";

    private static final UUID ORGANIZATION = UUID.fromString("88888888-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("88888888-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("88888888-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e1");
    private static final UUID PLANNER_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e2");
    private static final UUID VIEWER_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e3");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String carrierA;
    private static String carrierB;
    private static String tripA;
    private static String tripB;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String plannerToken;
    private String viewerToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_settlement_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'STL-ORG', 'Settlement Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'STL-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'STL-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'stl.admin@example.invalid', 'STL Admin'), ('" + PLANNER_AUTH
                + "', 'stl.planner@example.invalid', 'STL Planner'), ('" + VIEWER_AUTH
                + "', 'stl.viewer@example.invalid', 'STL Viewer')");
        membership("stl.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("stl.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("stl.planner@example.invalid", COMPANY_A, "PLANNER");
        membership("stl.viewer@example.invalid", COMPANY_A, "VIEWER");

        carrierA = carrier(COMPANY_A, "CARR-A");
        carrierB = carrier(COMPANY_B, "CARR-B");
        tripA = trip(COMPANY_A, "ORIGIN-A", carrierA, "1450.00");
        tripB = trip(COMPANY_B, "ORIGIN-B", carrierB, "999.00");
    }

    private static String carrier(UUID companyId, String code) {
        return idOf("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " SA', 'RUC', '20"
                + String.format("%09d", Math.abs(code.hashCode()) % 1_000_000_000) + "') RETURNING id");
    }

    /** A shipment with a cost row, which is what makes it comparable at all. */
    private static String trip(UUID companyId, String originCode, String carrierId, String expected) {
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', '" + originCode + "', 'Origin') RETURNING id");
        String run = idOf("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date,"
                + " status) VALUES ('" + companyId + "', 'PL-" + originCode + "', '" + origin
                + "', '2026-04-01', 'DRAFT') RETURNING id");
        // ck_trip_carrier_requires_vehicle (V11): a shipment naming a carrier names the vehicle
        // that carrier is running. The fixture obeys every existing invariant, not only V46's.
        String vehicleType = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + originCode
                + "', 'Rigid', 10000, 40, 20) RETURNING id");
        String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate,"
                + " carrier_id) VALUES ('" + companyId + "', '" + vehicleType + "', 'VEH-" + originCode
                + "', 'PLT-" + originCode + "', '" + carrierId + "') RETURNING id");
        String trip = idOf("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number,"
                + " carrier_id, vehicle_id) VALUES ('" + companyId + "', '" + run + "', '2026-04-01', 1, '"
                + carrierId + "', '" + vehicle + "') RETURNING id");
        // ck_trip_cost_estimate_complete (V30): an estimate carries the card that produced it, or
        // it is not an estimate. That snapshot is the whole reason a settled figure stays defensible
        // after the tariff moves, so the fixture supplies it rather than working around the rule.
        // ck_rate_card_has_a_component (V30/V39): a card that charges nothing is not an agreement.
        String card = idOf("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency,"
                + " base_amount, valid_from) VALUES ('" + companyId + "', 'RC-" + originCode + "', 'Card "
                + originCode + "', '" + carrierId + "', 'CARRIER', 'PEN', " + expected
                + ", '2026-01-01') RETURNING id");
        execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, estimated_amount,"
                + " estimated_at, rate_card_id, rate_card_code, rate_card_scope) VALUES ('" + companyId
                + "', '" + trip + "', '2026-04-01', 'PEN', " + expected + ", now(), '" + card + "', 'RC-"
                + originCode + "', 'CARRIER')");
        return trip;
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

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // --- helpers -------------------------------------------------------------------

    private String invoiceBody(String carrierId, String number, String total, String tripId, String lineAmount) {
        String line = tripId == null
                ? "{\"description\":\"Monthly surcharge\",\"lineAmount\":" + lineAmount + "}"
                : "{\"tripId\":\"" + tripId + "\",\"description\":\"Linehaul\",\"lineAmount\":" + lineAmount + "}";
        return "{\"carrierId\":\"" + carrierId + "\",\"invoiceNumber\":\"" + number
                + "\",\"invoiceDate\":\"2026-04-05\",\"currency\":\"PEN\",\"totalAmount\":" + total
                + ",\"lines\":[" + line + "]}";
    }

    private String nextNumber(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet();
    }

    private String receive(String body, UUID companyId) throws Exception {
        String response = mockMvc.perform(asAdmin(post(INVOICES), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void tolerance(UUID companyId, String percentage) {
        execute("INSERT INTO tms.tolerance_policy (company_id, percentage) VALUES ('" + companyId
                + "', " + percentage + ") ON CONFLICT DO NOTHING");
    }

    // --- the workflow --------------------------------------------------------------

    @Nested
    @DisplayName("the freight audit workflow")
    class Workflow {

        @Test
        @DisplayName("an invoice within tolerance matches, is approved and is exported")
        void happyPath() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("OK"), "1480.00", tripA, "1480.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.match.status").value("MATCHED"))
                    // The reasoning is on the record, not hidden behind the status.
                    .andExpect(jsonPath("$.match.expectedAmount").value(1450.00))
                    .andExpect(jsonPath("$.match.differenceAmount").value(30.00));

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Within tolerance.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.approvals[0].decision").value("APPROVED"));

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/export"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.exportReference").value("TMS-" + id))
                    .andExpect(jsonPath("$.alreadyExported").value(false));
        }

        @Test
        @DisplayName("an invoice outside tolerance becomes a discrepancy and cannot be approved")
        void discrepancyBlocksApproval() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("BAD"), "1800.00", tripA, "1800.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISCREPANCY"))
                    .andExpect(jsonPath("$.discrepancies[0].detail").isNotEmpty());

            // THE rule: no path from a discrepancy to payable without a person looking.
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("review alone is not enough - every difference has to be dealt with first")
        void reviewDoesNotClearDifferences() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("REV"), "1800.00", tripA, "1800.00"), COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/review"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict());
        }

        /** Unknown is never zero - the rule carried from V45 into the money. */
        @Test
        @DisplayName("a line billing no shipment is UNMATCHABLE, not a total overcharge")
        void unpricedIsUnmatchable() throws Exception {
            String id = receive(invoiceBody(carrierA, nextNumber("ACC"), "200.00", null, "200.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.match.status").value("UNMATCHABLE"))
                    // Null, never 0.00.
                    .andExpect(jsonPath("$.match.expectedAmount").doesNotExist());
        }

        @Test
        @DisplayName("the same carrier cannot bill the same number twice")
        void duplicateIsRefused() throws Exception {
            String number = nextNumber("DUP");
            receive(invoiceBody(carrierA, number, "100.00", tripA, "100.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(INVOICES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invoiceBody(carrierA, number, "100.00", tripA, "100.00")))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a rejection must say why")
        void rejectionNeedsAReason() throws Exception {
            String id = receive(invoiceBody(carrierA, nextNumber("REJ"), "100.00", tripA, "100.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/reject"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/reject"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"Billed for a shipment we did not order.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
        }
    }

    // --- authority -----------------------------------------------------------------

    @Nested
    @DisplayName("who may do what")
    class Authority {

        /**
         * The control that matters most: whoever works the audit queue must not be able to sign off
         * their own conclusions. A planner matches and cannot approve.
         */
        @Test
        @DisplayName("a planner can match but cannot approve or export")
        void plannerCannotApprove() throws Exception {
            String id = receive(invoiceBody(carrierA, nextNumber("AUTH"), "1450.00", tripA, "1450.00"), COMPANY_A);

            mockMvc.perform(asPlanner(post(INVOICES + "/" + id + "/match"), COMPANY_A))
                    .andExpect(status().isOk());
            mockMvc.perform(asPlanner(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asPlanner(post(INVOICES + "/" + id + "/export"), COMPANY_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a viewer can read the queue and change nothing")
        void viewerIsReadOnly() throws Exception {
            String id = receive(invoiceBody(carrierA, nextNumber("VIEW"), "1450.00", tripA, "1450.00"), COMPANY_A);

            mockMvc.perform(asViewer(get(INVOICES + "/" + id), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(post(INVOICES + "/" + id + "/match"), COMPANY_A))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asViewer(post(INVOICES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invoiceBody(carrierA, nextNumber("VIEW"), "1.00", tripA, "1.00")))
                    .andExpect(status().isForbidden());
        }
    }

    // --- tenancy -------------------------------------------------------------------

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("another company cannot read, match, approve or export this company's invoice")
        void crossCompanyIsBlocked() throws Exception {
            String id = receive(invoiceBody(carrierA, nextNumber("TEN"), "1450.00", tripA, "1450.00"), COMPANY_A);

            mockMvc.perform(asAdmin(get(INVOICES + "/" + id), COMPANY_B)).andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_B))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/export"), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an invoice cannot name another company's carrier")
        void carrierMustBelongToTheCompany() throws Exception {
            mockMvc.perform(asAdmin(post(INVOICES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invoiceBody(carrierB, nextNumber("XC"), "100.00", tripA, "100.00")))
                    .andExpect(status().isBadRequest());
        }

        /**
         * A line cannot even be recorded against another company's shipment.
         *
         * <p>Written expecting the invoice to be accepted and then matched as UNMATCHABLE. It is
         * refused at insert instead, because {@code fk_carrier_invoice_line_trip_company} makes the
         * reference unrepresentable - <b>the isolation is stronger than the test assumed</b>, which
         * is the right direction to be surprised in. The service now refuses it first with a
         * sentence naming how many lines are wrong, so an operator gets an explanation rather than
         * a constraint violation.
         */
        @Test
        @DisplayName("a line cannot bill another company's shipment at all")
        void foreignTripIsRefused() throws Exception {
            mockMvc.perform(asAdmin(post(INVOICES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invoiceBody(carrierA, nextNumber("XT"), "999.00", tripB, "999.00")))
                    .andExpect(status().isBadRequest());

            // And nothing was written on the way to refusing it.
            assertThat(count("SELECT count(*) FROM tms.carrier_invoice_line WHERE trip_id = '" + tripB + "'"))
                    .isZero();
        }
    }

    // --- concurrency ---------------------------------------------------------------

    @Nested
    @DisplayName("two people at once")
    class Concurrency {

        /**
         * What {@code uq_payable_export_invoice} is for. Two clicks on Export at the same instant
         * must produce <b>one</b> obligation - a second would be a second payment somebody has to
         * chase.
         */
        @Test
        @DisplayName("two simultaneous exports produce exactly one obligation")
        void twoExportsOneObligation() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("EXP"), "1450.00", tripA, "1450.00"), COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<Boolean> first = pool.submit(() -> attemptExport(id, ready, go));
                Future<Boolean> second = pool.submit(() -> attemptExport(id, ready, go));
                ready.await();
                go.countDown();
                first.get();
                second.get();
            } finally {
                pool.shutdownNow();
            }

            assertThat(count("SELECT count(*) FROM tms.payable_export WHERE carrier_invoice_id = '" + id + "'"))
                    .as("two simultaneous exports must leave exactly one obligation")
                    .isEqualTo(1);
        }

        private boolean attemptExport(String id, CountDownLatch ready, CountDownLatch go) {
            try {
                ready.countDown();
                go.await();
                mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/export"), COMPANY_A));
                return true;
            } catch (Exception refused) {
                // A conflict, a lock timeout or a unique violation are all refusals, which is the
                // outcome under test for the losing thread.
                return false;
            }
        }

        /**
         * Approval is the other decision that must happen once. The invoice is locked and versioned,
         * so the second attempt finds a state that no longer allows it.
         */
        @Test
        @DisplayName("two simultaneous approvals leave exactly one approval row")
        void twoApprovalsOneDecision() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("APP"), "1450.00", tripA, "1450.00"), COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A)).andExpect(status().isOk());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<Boolean> first = pool.submit(() -> attemptApprove(id, ready, go));
                Future<Boolean> second = pool.submit(() -> attemptApprove(id, ready, go));
                ready.await();
                go.countDown();
                first.get();
                second.get();
            } finally {
                pool.shutdownNow();
            }

            assertThat(count("SELECT count(*) FROM tms.settlement_approval WHERE carrier_invoice_id = '"
                    + id + "' AND decision = 'APPROVED'"))
                    .as("an expenditure is authorised once")
                    .isEqualTo(1);
        }

        private boolean attemptApprove(String id, CountDownLatch ready, CountDownLatch go) {
            try {
                ready.countDown();
                go.await();
                mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"));
                return true;
            } catch (Exception refused) {
                return false;
            }
        }
    }

    // --- plumbing ------------------------------------------------------------------

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asPlanner(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + plannerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the settlement fixture", failed);
        }
    }

    private static long count(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the settlement fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the settlement fixture", failed);
        }
    }
}
