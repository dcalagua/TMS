package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    /** A second person who may approve: maker/checker needs two, or it proves nothing. */
    private static final UUID CHECKER_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e4");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String carrierA;
    private static String carrierB;
    /** COMPANY_A's one agreement with carrierA; every per-test shipment is estimated from it. */
    private static String cardA;
    private static String tripB;

    /**
     * This test's own shipment, created fresh in {@link #mintTokens()}.
     *
     * <p><b>Not static, and that is now load-bearing.</b> It used to be one shipment shared by
     * every test in the class, which was invisible until settlement learned to notice a shipment
     * billed on two invoices: the first test to leave a live invoice against it made every later
     * test's invoice a {@code DUPLICATE_INVOICE}, and which tests those were depended on JUnit's
     * ordering. The shared fixture was always making a claim these tests did not intend - that
     * nothing else bills this shipment - and one shipment per test is what makes the claim true.
     */
    private String tripA;

    @Autowired
    private MockMvc mockMvc;

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
                + "', 'stl.viewer@example.invalid', 'STL Viewer'), ('" + CHECKER_AUTH
                + "', 'stl.checker@example.invalid', 'STL Checker')");
        membership("stl.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("stl.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("stl.planner@example.invalid", COMPANY_A, "PLANNER");
        membership("stl.viewer@example.invalid", COMPANY_A, "VIEWER");
        membership("stl.checker@example.invalid", COMPANY_A, "COMPANY_ADMIN");

        carrierA = carrier(COMPANY_A, "CARR-A");
        carrierB = carrier(COMPANY_B, "CARR-B");
        cardA = rateCard(COMPANY_A, "RC-A", carrierA, "1450.00");
        String cardB = rateCard(COMPANY_B, "RC-B", carrierB, "999.00");
        tripB = trip(COMPANY_B, "ORIGIN-B", carrierB, cardB, "RC-B", "999.00");
    }

    /**
     * The one agreement a carrier has with a company, seeded once per class.
     *
     * <p>{@code uq_rate_card_active_agreement} (V30) refuses two identical active agreements -
     * same company, carrier, scope, narrowing and start date - and it is right to. When the
     * shipment moved to {@code @BeforeEach}, the card that priced it moved with it, and every test
     * after the first tried to sign the same agreement again: the database is one per class, so
     * only the first test ever got its fixture. Sharing the card is safe where sharing the shipment
     * was not, because nothing in settlement reads the card - matching compares against each
     * shipment's own {@code trip_cost} snapshot, and duplicate detection keys on the shipment.
     * ck_rate_card_has_a_component (V30/V39): a card that charges nothing is not an agreement.
     */
    private static String rateCard(UUID companyId, String code, String carrierId, String baseAmount) {
        return idOf("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency,"
                + " base_amount, valid_from) VALUES ('" + companyId + "', '" + code + "', 'Card " + code
                + "', '" + carrierId + "', 'CARRIER', 'PEN', " + baseAmount + ", '2026-01-01') RETURNING id");
    }

    private static String carrier(UUID companyId, String code) {
        return idOf("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " SA', 'RUC', '20"
                + String.format("%09d", Math.abs(code.hashCode()) % 1_000_000_000) + "') RETURNING id");
    }

    /** A shipment with a cost row, which is what makes it comparable at all. */
    private static String trip(UUID companyId, String originCode, String carrierId, String cardId,
            String cardCode, String expected) {
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
        // The card is the company's one agreement (see rateCard); the estimate is this shipment's own.
        execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, estimated_amount,"
                + " estimated_at, rate_card_id, rate_card_code, rate_card_scope) VALUES ('" + companyId
                + "', '" + trip + "', '2026-04-01', 'PEN', " + expected + ", now(), '" + cardId + "', '"
                + cardCode + "', 'CARRIER')");
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
        checkerToken = TestJwts.validFor(CHECKER_AUTH);
        // Priced at 1450.00, like the shared one it replaces, so every existing expectation in this
        // class still reads against the same figure. The code stays short because
        // ck_vehicle_license_plate_shape caps the derived plate at 12 characters.
        // A fresh shipment on the shared agreement: the card may be common, a billed shipment may not.
        tripA = trip(COMPANY_A, "A" + SEQUENCE.incrementAndGet(), carrierA, cardA, "RC-A", "1450.00");
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

            // Approved by somebody other than whoever recorded it - maker/checker, enforced since
            // the JOB 20 post-check.
            mockMvc.perform(asChecker(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
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

        /**
         * The duplicate {@code uq_carrier_invoice_number} cannot see.
         *
         * <p>Two documents, two numbers, one shipment. Nothing in the database refuses this - and
         * nothing should, because a carrier re-billing after a credit note is legal - so the
         * second one has to come out of matching as a difference a person must deal with rather
         * than as a clean match somebody can approve straight through.
         */
        @Test
        @DisplayName("a shipment already billed on another invoice is a duplicate, not a clean match")
        void sameShipmentOnTwoInvoicesIsFlagged() throws Exception {
            tolerance(COMPANY_A, "3");
            String first = receive(invoiceBody(carrierA, nextNumber("TWICE"), "1480.00", tripA, "1480.00"),
                    COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + first + "/match"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MATCHED"));

            // A different number for the same shipment. The insert is allowed; the match is not clean.
            String second = receive(invoiceBody(carrierA, nextNumber("TWICE"), "1480.00", tripA, "1480.00"),
                    COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + second + "/match"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISCREPANCY"))
                    .andExpect(jsonPath("$.discrepancies[?(@.type == 'DUPLICATE_INVOICE')]").isNotEmpty());

            // And it cannot be approved past, which is the whole point.
            mockMvc.perform(asChecker(post(INVOICES + "/" + second + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict());
        }

        /**
         * A refused invoice bills nothing.
         *
         * <p>Without this exclusion the rule would eat itself: every legitimate re-bill after a
         * rejection would be reported as a duplicate of the document it replaces, and the queue
         * would fill with differences nobody can resolve.
         */
        @Test
        @DisplayName("a rejected invoice does not make the replacement a duplicate")
        void rejectedInvoiceDoesNotBlockItsReplacement() throws Exception {
            tolerance(COMPANY_A, "3");
            String refused = receive(invoiceBody(carrierA, nextNumber("CN"), "1480.00", tripA, "1480.00"),
                    COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + refused + "/reject"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"Wrong tariff; credit note requested.\"}"))
                    .andExpect(status().isOk());

            String replacement = receive(invoiceBody(carrierA, nextNumber("CN"), "1480.00", tripA, "1480.00"),
                    COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + replacement + "/match"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MATCHED"));
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

    // --- maker / checker -----------------------------------------------------------

    @Nested
    @DisplayName("maker and checker are two people")
    class MakerChecker {

        /**
         * <b>The post-check that found this.</b> JOB 20 claimed "whoever keys an invoice cannot
         * approve their own" on the strength of two separate permissions. Separate permissions are
         * <em>separable</em>, not separated - one account can hold both, and until now nothing
         * stopped it. This is the test that makes the claim true rather than aspirational.
         */
        @Test
        @DisplayName("the person who recorded an invoice cannot approve it, even holding the permission")
        void creatorCannotApproveOwnInvoice() throws Exception {
            tolerance(COMPANY_A, "3");
            // Recorded by the admin, who also holds settlement.invoice:approve.
            String id = receive(invoiceBody(carrierA, nextNumber("MK1"), "1450.00", tripA, "1450.00"), COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict());

            // And nothing was written on the way to refusing it.
            assertThat(count("SELECT count(*) FROM tms.settlement_approval WHERE carrier_invoice_id = '"
                    + id + "'")).isZero();
        }

        @Test
        @DisplayName("a second person holding the permission can approve it")
        void anotherApproverCan() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("MK2"), "1450.00", tripA, "1450.00"), COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asChecker(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        @DisplayName("a second person WITHOUT the permission still cannot")
        void secondPersonStillNeedsThePermission() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("MK3"), "1450.00", tripA, "1450.00"), COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A)).andExpect(status().isOk());

            // The planner is not the maker, and being a different person is not authority.
            mockMvc.perform(asPlanner(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an approver from another company cannot approve it either")
        void crossTenantApproverIsBlocked() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(invoiceBody(carrierA, nextNumber("MK4"), "1450.00", tripA, "1450.00"), COMPANY_A);

            // The admin is a member of COMPANY_B too, and still cannot reach COMPANY_A's invoice
            // through it: tenancy is checked before anything else.
            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/approve"), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }

        /**
         * The asymmetry is deliberate. Refusing to pay commits nothing and creates no obligation, so
         * a clerk who spots their own keying error must be able to reject it rather than needing a
         * second person to undo their own mistake. The control exists to stop money leaving.
         */
        @Test
        @DisplayName("the creator CAN reject their own invoice - refusing to pay commits nothing")
        void creatorMayRejectOwnInvoice() throws Exception {
            String id = receive(invoiceBody(carrierA, nextNumber("MK5"), "1450.00", tripA, "1450.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/reject"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"Keyed against the wrong shipment.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
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
            mockMvc.perform(asChecker(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
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
                mockMvc.perform(asChecker(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"));
                return true;
            } catch (Exception refused) {
                return false;
            }
        }
    }

    // --- phase 50 · V64 · ADR-024 part 1 ---------------------------------------------

    private static final String TOLERANCES = "/api/v1/settlement/tolerance-policies";

    /** An invoice whose lines are (tripId, chargeCode, amount) triples; a null code is omitted. */
    private String codedInvoice(String number, String total, String... lines) {
        StringBuilder rendered = new StringBuilder();
        for (int index = 0; index < lines.length; index += 3) {
            if (index > 0) {
                rendered.append(',');
            }
            rendered.append("{\"tripId\":\"").append(lines[index]).append("\",\"description\":\"Line ")
                    .append(index / 3 + 1).append('"');
            if (lines[index + 1] != null) {
                rendered.append(",\"chargeCode\":\"").append(lines[index + 1]).append('"');
            }
            rendered.append(",\"lineAmount\":").append(lines[index + 2]).append('}');
        }
        return "{\"carrierId\":\"" + carrierA + "\",\"invoiceNumber\":\"" + number
                + "\",\"invoiceDate\":\"2026-04-05\",\"currency\":\"PEN\",\"totalAmount\":" + total
                + ",\"lines\":[" + rendered + "]}";
    }

    /** The estimate of {@code tripA} broken down as BASE 1450 - what a rate card with only a base produces. */
    private void baseComponentFor(String tripId) {
        execute("INSERT INTO tms.trip_cost_component (trip_cost_id, company_id, component, status, amount)"
                + " SELECT id, company_id, 'BASE', 'APPLIED', estimated_amount FROM tms.trip_cost"
                + " WHERE trip_id = '" + tripId + "'");
    }

    private String matchAndRead(String id) throws Exception {
        return mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/match"), COMPANY_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("charge codes, match basis and per-line discrepancies (V64)")
    class ChargeCodes {

        @Test
        @DisplayName("freight and a toll of one shipment are one bill: summed, no duplicate")
        void twoChargesOfOneShipmentMatch() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(codedInvoice(nextNumber("CC"), "1480.00",
                    tripA, "FREIGHT", "1450.00", tripA, "TOLL", "30.00"), COMPANY_A);

            String body = matchAndRead(id);
            assertThat((String) JsonPath.read(body, "$.match.status")).isEqualTo("MATCHED");
            assertThat((String) JsonPath.read(body, "$.match.expectedBasis")).isEqualTo("ESTIMATED");
            assertThat((java.util.List<?>) JsonPath.read(body, "$.discrepancies")).isEmpty();
            assertThat((String) JsonPath.read(body, "$.lines[1].chargeCode")).isEqualTo("TOLL");
            assertThat(count("SELECT count(*) FROM tms.carrier_invoice_line WHERE carrier_invoice_id = '" + id
                    + "' AND charge_code = 'TOLL'")).isEqualTo(1);
        }

        @Test
        @DisplayName("a line without a code is stored null and read as FREIGHT")
        void uncodedLineIsFreight() throws Exception {
            String id = receive(codedInvoice(nextNumber("NC"), "1450.00", tripA, null, "1450.00"), COMPANY_A);

            mockMvc.perform(asAdmin(get(INVOICES + "/" + id), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines[0].chargeCode").value("FREIGHT"));
            assertThat(count("SELECT count(*) FROM tms.carrier_invoice_line WHERE carrier_invoice_id = '" + id
                    + "' AND charge_code IS NULL")).isEqualTo(1);
        }

        @Test
        @DisplayName("out of tolerance: the difference is raised on the line that makes it, with invoice_line_id")
        void discrepancyCarriesItsLine() throws Exception {
            tolerance(COMPANY_A, "3");
            baseComponentFor(tripA);
            // BASE as estimated; a toll the estimate never charged.
            String id = receive(codedInvoice(nextNumber("LN"), "1750.00",
                    tripA, "BASE", "1450.00", tripA, "TOLL", "300.00"), COMPANY_A);

            String body = matchAndRead(id);
            String tollLine = JsonPath.read(body, "$.lines[1].id");
            assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DISCREPANCY");
            assertThat(count("SELECT count(*) FROM tms.freight_discrepancy WHERE carrier_invoice_id = '" + id
                    + "' AND type = 'LINE_AMOUNT' AND invoice_line_id = '" + tollLine + "'")).isEqualTo(1);
            // The BASE line matched its component and opened nothing.
            assertThat(count("SELECT count(*) FROM tms.freight_discrepancy WHERE carrier_invoice_id = '" + id
                    + "' AND type = 'LINE_AMOUNT'")).isEqualTo(1);
            assertThat(((Number) JsonPath.read(body, "$.lines[1].expectedAmount")).doubleValue()).isZero();
            assertThat((String) JsonPath.read(body, "$.lines[1].expectedBasis")).isEqualTo("ESTIMATED");
            assertThat((java.util.List<String>) JsonPath.read(body, "$.discrepancies[*].invoiceLineId"))
                    .contains(tollLine);
        }

        @Test
        @DisplayName("within tolerance the per-line comparison is shown and opens nothing")
        void withinToleranceShowsButOpensNothing() throws Exception {
            tolerance(COMPANY_A, "3");
            baseComponentFor(tripA);
            String id = receive(codedInvoice(nextNumber("WT"), "1470.00",
                    tripA, "BASE", "1450.00", tripA, "TOLL", "20.00"), COMPANY_A);

            String body = matchAndRead(id);
            assertThat((String) JsonPath.read(body, "$.match.status")).isEqualTo("MATCHED");
            assertThat(((Number) JsonPath.read(body, "$.lines[1].differenceAmount")).doubleValue()).isEqualTo(20.0);
            assertThat(count("SELECT count(*) FROM tms.freight_discrepancy WHERE carrier_invoice_id = '" + id
                    + "'")).isZero();
        }

        @Test
        @DisplayName("a closed cost is compared against its actual, and the match says ACTUAL")
        void closedCostIsTheBasis() throws Exception {
            execute("UPDATE tms.trip_cost SET actual_amount = 1600.00, actual_recorded_at = now(),"
                    + " closed_at = now(), closed_by = (SELECT id FROM tms.app_user"
                    + " WHERE email = 'stl.admin@example.invalid') WHERE trip_id = '" + tripA + "'");
            String id = receive(codedInvoice(nextNumber("AC"), "1600.00", tripA, "FREIGHT", "1600.00"), COMPANY_A);

            String body = matchAndRead(id);
            assertThat((String) JsonPath.read(body, "$.match.status")).isEqualTo("MATCHED");
            assertThat((String) JsonPath.read(body, "$.match.expectedBasis")).isEqualTo("ACTUAL");
            assertThat(count("SELECT count(*) FROM tms.freight_match WHERE carrier_invoice_id = '" + id
                    + "' AND expected_basis = 'ACTUAL' AND expected_amount = 1600.00")).isEqualTo(1);
        }

        @Test
        @DisplayName("across invoices a duplicate is the same shipment AND charge")
        void duplicateAcrossInvoicesIsPerCharge() throws Exception {
            String first = receive(codedInvoice(nextNumber("D1"), "1450.00", tripA, "FREIGHT", "1450.00"), COMPANY_A);
            String toll = receive(codedInvoice(nextNumber("D2"), "30.00", tripA, "TOLL", "30.00"), COMPANY_A);
            String again = receive(codedInvoice(nextNumber("D3"), "1450.00", tripA, "FREIGHT", "1450.00"), COMPANY_A);

            matchAndRead(toll);
            matchAndRead(again);
            assertThat(count("SELECT count(*) FROM tms.freight_discrepancy WHERE carrier_invoice_id = '" + toll
                    + "' AND type = 'DUPLICATE_INVOICE'")).isZero();
            assertThat(count("SELECT count(*) FROM tms.freight_discrepancy WHERE carrier_invoice_id = '" + again
                    + "' AND type = 'DUPLICATE_INVOICE' AND invoice_line_id IS NOT NULL")).isEqualTo(1);
            assertThat(first).isNotBlank();
        }

        @Test
        @DisplayName("an unknown charge code is refused before it reaches the database")
        void unknownChargeCodeIsRefused() throws Exception {
            mockMvc.perform(asAdmin(post(INVOICES), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content(codedInvoice(nextNumber("UK"), "10.00", tripA, "BRIBE", "10.00")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("CSV export (V64)")
    class CsvExport {

        @Test
        @DisplayName("an approved invoice exports as CSV; a repeat in JSON returns the CSV it already made")
        void csvExportIsIdempotentInItsFirstFormat() throws Exception {
            tolerance(COMPANY_A, "3");
            String id = receive(codedInvoice(nextNumber("CSV"), "1480.00",
                    tripA, "FREIGHT", "1450.00", tripA, "TOLL", "30.00"), COMPANY_A);
            matchAndRead(id);
            mockMvc.perform(asChecker(post(INVOICES + "/" + id + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            String body = mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/export").param("format", "CSV"),
                            COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.format").value("CSV"))
                    .andExpect(jsonPath("$.exportReference").value("TMS-" + id))
                    .andReturn().getResponse().getContentAsString();
            String payload = JsonPath.read(body, "$.payload");
            assertThat(payload).startsWith("reference,invoiceId,").contains(",TOLL,").contains("\r\n");
            assertThat(payload.split("\r\n")).hasSize(3);

            mockMvc.perform(asAdmin(post(INVOICES + "/" + id + "/export").param("format", "JSON"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.format").value("CSV"))
                    .andExpect(jsonPath("$.alreadyExported").value(true));
        }
    }

    /**
     * The tolerance endpoints, in company B so the 3 % company policy every other test in this class
     * leans on is never touched.
     */
    @Nested
    @DisplayName("tolerance policies (V64)")
    class TolerancePolicies {

        private String create(String body) throws Exception {
            String response = mockMvc.perform(asAdmin(post(TOLERANCES), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(response, "$.id");
        }

        @Test
        @DisplayName("create, one active per scope, change, retire - each audited")
        void lifecycle() throws Exception {
            execute("UPDATE tms.tolerance_policy SET active = false WHERE company_id = '" + COMPANY_B + "'");
            String company = create("{\"absoluteAmount\":10,\"currency\":\"pen\",\"percentage\":2}");

            mockMvc.perform(asAdmin(post(TOLERANCES), COMPANY_B).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"percentage\":5}"))
                    .andExpect(status().isConflict());

            String carrier = create("{\"carrierId\":\"" + carrierB + "\",\"percentage\":4}");

            mockMvc.perform(asAdmin(put(TOLERANCES + "/" + company), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"percentage\":2.5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.percentage").value(2.5))
                    .andExpect(jsonPath("$.absoluteAmount").doesNotExist());

            mockMvc.perform(asAdmin(post(TOLERANCES + "/" + carrier + "/deactivate"), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            mockMvc.perform(asAdmin(get(TOLERANCES), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].carrierId").doesNotExist());
            mockMvc.perform(asAdmin(get(TOLERANCES).param("includeInactive", "true"), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + carrier + "')].carrierName").value("CARR-B SA"));

            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_type = 'TOLERANCE_POLICY'"
                    + " AND action = 'TOLERANCE_POLICY_CHANGED' AND aggregate_id IN ('" + company + "', '"
                    + carrier + "')")).isEqualTo(4);
            assertThat(count("SELECT count(*) FROM tms.tolerance_policy WHERE id = '" + company
                    + "' AND currency IS NULL AND absolute_amount IS NULL")).isEqualTo(1);
        }

        @Test
        @DisplayName("an absolute bound needs its currency, and some bound is needed")
        void boundsAreValidated() throws Exception {
            mockMvc.perform(asAdmin(post(TOLERANCES), COMPANY_B).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"absoluteAmount\":10}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(post(TOLERANCES), COMPANY_B).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(post(TOLERANCES), COMPANY_B).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"percentage\":101}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reading needs settlement.invoice:read; writing needs settlement.tolerance:manage")
        void permissions() throws Exception {
            mockMvc.perform(asViewer(get(TOLERANCES), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(post(TOLERANCES), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"percentage\":50}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asPlanner(post(TOLERANCES), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"percentage\":50}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("another company's policy is not found, and its carrier cannot be named")
        void tenantIsolation() throws Exception {
            String foreign = idOf("INSERT INTO tms.tolerance_policy (company_id, carrier_id, percentage, active)"
                    + " VALUES ('" + COMPANY_B + "', '" + carrierB + "', 1, false) RETURNING id");
            mockMvc.perform(asAdmin(put(TOLERANCES + "/" + foreign), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"percentage\":9}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(post(TOLERANCES), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"carrierId\":\"" + carrierB + "\",\"percentage\":9}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("settlement KPIs (V64)")
    class SettlementKpis {

        @Test
        @DisplayName("the KPI report carries the settlement section for a caller who may read invoices")
        void reportHasSettlement() throws Exception {
            String id = receive(codedInvoice(nextNumber("KPI"), "1450.00", tripA, "FREIGHT", "1450.00"), COMPANY_A);
            matchAndRead(id);

            mockMvc.perform(asAdmin(get("/api/v1/reporting/kpis").param("from", "2026-04-01")
                            .param("to", "2026-04-30"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.settlement.invoicesReceived").isNumber())
                    .andExpect(jsonPath("$.settlement.byCarrier[0].currency").value("PEN"))
                    .andExpect(jsonPath("$.settlement.byCarrier[0].carrierName").value("CARR-A SA"));
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

    private MockHttpServletRequestBuilder asChecker(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + checkerToken)
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
