package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.settlement.application.AccrualSweep;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
 * Freight accruals end to end (migration V65, ADR-023 section 1, phase 55).
 *
 * <p>The full cycle through the API and the sweep - accrue, adjust to the closed actual, consume
 * partially and totally by approved invoices, close the remainder, reverse - plus what no unit test
 * can prove: the sweep is idempotent run twice and run concurrently, the movements cannot be edited
 * by the runtime role or by the owner, the accounting date is the company's calendar day, trips that
 * cannot be priced are listed and never accrued, own fleet is left out, and another company sees none
 * of it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AccrualApiIntegrationTest.JwtDecoderOverride.class)
class AccrualApiIntegrationTest {

    private static final String ACCRUALS = "/api/v1/settlement/accruals";
    private static final String INVOICES = "/api/v1/settlement/invoices";
    private static final String GL = "/api/v1/settlement/gl-mappings";

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("99999999-0000-4000-8000-0000000000c1");
    /** Accrues at delivery. */
    private static final UUID COMPANY_B = UUID.fromString("99999999-0000-4000-8000-0000000000c2");
    /** Only the journal test writes here, so its period holds exactly what that test put in it. */
    private static final UUID COMPANY_C = UUID.fromString("99999999-0000-4000-8000-0000000000c3");
    private static final UUID ADMIN_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e1");
    private static final UUID PLANNER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e2");
    private static final UUID VIEWER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e3");
    private static final UUID CHECKER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e4");

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String adminUserId;
    private static String carrierA;
    private static String carrierB;
    private static String carrierC;
    private static String cardA;
    private static String cardB;
    private static String cardC;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccrualSweep accrualSweep;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_accrual_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'ACR-ORG', 'Accrual Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'ACR-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'ACR-B', 'Company B', 'America/Lima'), ('"
                + COMPANY_C + "', '" + ORGANIZATION + "', 'ACR-C', 'Company C', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'acr.admin@example.invalid', 'ACR Admin'), ('" + PLANNER_AUTH
                + "', 'acr.planner@example.invalid', 'ACR Planner'), ('" + VIEWER_AUTH
                + "', 'acr.viewer@example.invalid', 'ACR Viewer'), ('" + CHECKER_AUTH
                + "', 'acr.checker@example.invalid', 'ACR Checker')");
        adminUserId = idOf("SELECT id FROM tms.app_user WHERE email = 'acr.admin@example.invalid'");
        membership("acr.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("acr.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("acr.admin@example.invalid", COMPANY_C, "COMPANY_ADMIN");
        membership("acr.planner@example.invalid", COMPANY_A, "PLANNER");
        membership("acr.viewer@example.invalid", COMPANY_A, "VIEWER");
        membership("acr.checker@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("acr.checker@example.invalid", COMPANY_C, "COMPANY_ADMIN");

        carrierA = carrier(COMPANY_A, "ACR-CA");
        carrierB = carrier(COMPANY_B, "ACR-CB");
        carrierC = carrier(COMPANY_C, "ACR-CC");
        cardA = rateCard(COMPANY_A, "RC-ACR-A", carrierA);
        cardB = rateCard(COMPANY_B, "RC-ACR-B", carrierB);
        cardC = rateCard(COMPANY_C, "RC-ACR-C", carrierC);
        // A wide tolerance, so an invoice below the figure matches and the tests reach approval
        // without working discrepancies they are not about.
        execute("INSERT INTO tms.tolerance_policy (company_id, percentage) VALUES ('" + COMPANY_A + "', 60), ('"
                + COMPANY_C + "', 60)");
        execute("INSERT INTO tms.company_settings (company_id, accrual_trigger) VALUES ('" + COMPANY_B
                + "', 'DELIVERED')");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
        checkerToken = TestJwts.validFor(CHECKER_AUTH);
    }

    // ------------------------------------------------------------------ the cycle

    @Nested
    @DisplayName("the accrual cycle")
    class Cycle {

        @Test
        @DisplayName("accrue -> adjust -> consume partially -> consume totally; the movements sum to the balance")
        void fullCycle() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "1450.00", OffsetDateTime.now().minusHours(20));

            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);
            assertThat(scalar("SELECT status || '/' || basis || '/' || accrued_amount FROM tms.freight_accrual"
                    + " WHERE id = '" + accrual + "'")).isEqualTo("OPEN/ESTIMATED/1450.00");
            // The sweep is a machine, and the audit trail says so.
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + accrual
                    + "' AND action = 'ACCRUAL_CREATED' AND actor_machine_label = 'system:accrual-sweep'"))
                    .isEqualTo(1);

            // Twice: nothing more.
            accrualSweep.sweepAll();
            assertThat(count("SELECT count(*) FROM tms.freight_accrual WHERE trip_id = '" + trip + "'")).isEqualTo(1);
            assertThat(entries(accrual)).isEqualTo(1);

            closeCost(trip, "1500.00");
            accrualSweep.sweepAll();
            accrualSweep.sweepAll();
            assertThat(scalar("SELECT status || '/' || basis || '/' || accrued_amount FROM tms.freight_accrual"
                    + " WHERE id = '" + accrual + "'")).isEqualTo("OPEN/ACTUAL/1500.00");
            assertThat(count("SELECT count(*) FROM tms.freight_accrual_entry WHERE accrual_id = '" + accrual
                    + "' AND entry_type = 'ADJUST' AND amount = 50.00 AND source_type = 'TRIP_COST_ACTUAL'"))
                    .isEqualTo(1);

            approve(invoice(COMPANY_A, carrierA, trip, null, "1000.00"), COMPANY_A);
            mockMvc.perform(asAdmin(get(ACCRUALS + "/" + accrual), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.status").value("PARTIALLY_SETTLED"))
                    .andExpect(jsonPath("$.summary.consumedAmount").value(1000.00))
                    .andExpect(jsonPath("$.summary.balance").value(500.00));

            approve(invoice(COMPANY_A, carrierA, trip, "TOLL", "500.00"), COMPANY_A);
            mockMvc.perform(asAdmin(get(ACCRUALS + "/" + accrual), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.status").value("SETTLED"))
                    .andExpect(jsonPath("$.summary.balance").value(0.00))
                    .andExpect(jsonPath("$.entries.length()").value(4))
                    .andExpect(jsonPath("$.entries[0].entryType").value("ACCRUE"))
                    .andExpect(jsonPath("$.entries[1].entryType").value("ADJUST"))
                    .andExpect(jsonPath("$.entries[2].entryType").value("CONSUME"))
                    .andExpect(jsonPath("$.entries[3].entryType").value("CONSUME"));

            assertBalanceIsTheSumOfItsMovements(accrual);
        }

        @Test
        @DisplayName("a partially settled accrual is closed by a person; the remainder is reversed, once")
        void closeRemainder() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "1450.00", OffsetDateTime.now().minusHours(10));
            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);
            approve(invoice(COMPANY_A, carrierA, trip, null, "1400.00"), COMPANY_A);

            mockMvc.perform(asAdmin(post(ACCRUALS + "/" + accrual + "/reverse"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(asAdmin(post(ACCRUALS + "/" + accrual + "/reverse"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Carrier billed in full; the rest is not owed.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.status").value("SETTLED"))
                    .andExpect(jsonPath("$.summary.reversedAmount").value(50.00))
                    .andExpect(jsonPath("$.summary.balance").value(0.00))
                    .andExpect(jsonPath("$.entries[2].entryType").value("REVERSE"))
                    .andExpect(jsonPath("$.entries[2].reason").value("Carrier billed in full; the rest is not owed."));

            mockMvc.perform(asAdmin(post(ACCRUALS + "/" + accrual + "/reverse"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Again\"}"))
                    .andExpect(status().isConflict());
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + accrual
                    + "' AND action = 'ACCRUAL_REVERSED'")).isEqualTo(1);
            assertBalanceIsTheSumOfItsMovements(accrual);
        }

        @Test
        @DisplayName("an open accrual is reversed, and a later invoice does not consume it")
        void reverseOpen() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "800.00", OffsetDateTime.now().minusHours(5));
            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);

            mockMvc.perform(asAdmin(post(ACCRUALS + "/" + accrual + "/reverse"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Service not billable\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.status").value("REVERSED"))
                    .andExpect(jsonPath("$.summary.balance").value(0.00));

            approve(invoice(COMPANY_A, carrierA, trip, null, "800.00"), COMPANY_A);
            assertThat(scalar("SELECT status FROM tms.freight_accrual WHERE id = '" + accrual + "'"))
                    .isEqualTo("REVERSED");
            assertThat(entries(accrual)).isEqualTo(2);
        }

        @Test
        @DisplayName("an invoice approved before the accrual existed is consumed when it is born")
        void approvedBeforeAccrued() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "900.00", OffsetDateTime.now().minusHours(3));
            approve(invoice(COMPANY_A, carrierA, trip, null, "900.00"), COMPANY_A);
            assertThat(count("SELECT count(*) FROM tms.freight_accrual WHERE trip_id = '" + trip + "'")).isZero();

            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);
            assertThat(scalar("SELECT status || '/' || consumed_amount FROM tms.freight_accrual WHERE id = '"
                    + accrual + "'")).isEqualTo("SETTLED/900.00");
            assertBalanceIsTheSumOfItsMovements(accrual);
        }

        @Test
        @DisplayName("approving the same invoice twice consumes once")
        void consumeOnce() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "700.00", OffsetDateTime.now().minusHours(2));
            accrualSweep.sweepAll();
            String invoice = invoice(COMPANY_A, carrierA, trip, null, "300.00");
            approve(invoice, COMPANY_A);
            mockMvc.perform(asChecker(post(INVOICES + "/" + invoice + "/approve"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
            assertThat(count("SELECT count(*) FROM tms.freight_accrual_entry WHERE carrier_invoice_id = '"
                    + invoice + "'")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ V66 (phase 60)

    @Nested
    @DisplayName("IGV and notes (V66)")
    class Taxes {

        /** A document billing one trip with a fiscal breakdown; returns its id. */
        private String fiscalDocument(String type, String referenced, String subtotal, String igv, String total,
                String trip) throws Exception {
            String body = "{\"carrierId\":\"" + carrierA + "\",\"invoiceNumber\":\"TAX-" + SEQUENCE.incrementAndGet()
                    + "\",\"invoiceDate\":\"2026-10-05\",\"currency\":\"PEN\",\"totalAmount\":" + total
                    + ",\"subtotalAmount\":" + subtotal + ",\"igvRate\":18,\"igvAmount\":" + igv
                    + (type == null ? "" : ",\"documentType\":\"" + type + "\",\"referencedInvoiceId\":\"" + referenced + "\"")
                    + ",\"lines\":[{\"tripId\":\"" + trip + "\",\"description\":\"Flete\",\"lineAmount\":" + subtotal
                    + "}]}";
            String response = mockMvc.perform(asAdmin(post(INVOICES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(response, "$.id");
        }

        @Test
        @DisplayName("a factura consumes by its subtotal, net of IGV; an approved credit note consumes nothing")
        void subtotalAndNotes() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "1000.00", OffsetDateTime.now().minusHours(4));
            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);

            String invoice = fiscalDocument(null, null, "1000.00", "180.00", "1180.00", trip);
            approve(invoice, COMPANY_A);
            // IGV is fiscal credit, not expense: 1000 consumed, not 1180.
            assertThat(scalar("SELECT status || '/' || consumed_amount FROM tms.freight_accrual WHERE id = '"
                    + accrual + "'")).isEqualTo("SETTLED/1000.00");

            String credit = fiscalDocument("07", invoice, "100.00", "18.00", "118.00", trip);
            approve(credit, COMPANY_A);
            assertThat(scalar("SELECT consumed_amount::text FROM tms.freight_accrual WHERE id = '" + accrual + "'"))
                    .isEqualTo("1000.00");
            assertThat(count("SELECT count(*) FROM tms.freight_accrual_entry WHERE carrier_invoice_id = '" + credit
                    + "'")).isZero();
            assertBalanceIsTheSumOfItsMovements(accrual);
        }
    }

    // ------------------------------------------------------------------ the sweep

    @Nested
    @DisplayName("the sweep")
    class Sweep {

        @Test
        @DisplayName("two sweeps at the same instant create one accrual")
        void concurrentSweeps() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "610.00", OffsetDateTime.now().minusHours(1));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<?>> runs = List.of(
                        pool.submit(() -> {
                            go.await();
                            return accrualSweep.sweepAll();
                        }),
                        pool.submit(() -> {
                            go.await();
                            return accrualSweep.sweepAll();
                        }));
                go.countDown();
                for (Future<?> run : runs) {
                    run.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(count("SELECT count(*) FROM tms.freight_accrual WHERE trip_id = '" + trip + "'")).isEqualTo(1);
            assertThat(entries(accrualOf(trip))).isEqualTo(1);
        }

        @Test
        @DisplayName("trips that cannot be priced are listed with their reason and never accrued; own fleet is left out")
        void unaccrued() throws Exception {
            OffsetDateTime when = OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, ZoneOffset.UTC);
            String noCost = dispatchedTrip(COMPANY_A, carrierA, cardA, null, when);
            String openActualOnly = dispatchedTrip(COMPANY_A, carrierA, cardA, null, when.plusMinutes(5));
            execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, actual_amount,"
                    + " actual_recorded_at) VALUES ('" + COMPANY_A + "', '" + openActualOnly + "', '2026-07-10',"
                    + " 'PEN', 300.00, now())");
            String ownFleet = ownFleetTrip(COMPANY_A, when.plusMinutes(10));

            mockMvc.perform(asAdmin(post(ACCRUALS + "/sweep?from=2026-07-10&to=2026-07-10"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created").value(0))
                    .andExpect(jsonPath("$.unaccrued").value(2));
            assertThat(count("SELECT count(*) FROM tms.freight_accrual WHERE trip_id IN ('" + noCost + "', '"
                    + openActualOnly + "', '" + ownFleet + "')")).isZero();

            String body = mockMvc.perform(asViewer(get(ACCRUALS + "/unaccrued?from=2026-07-10&to=2026-07-10"),
                            COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trigger").value("DISPATCHED"))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andReturn().getResponse().getContentAsString();
            List<String> reasons = JsonPath.read(body, "$.rows[?(@.tripId == '" + noCost + "')].reason");
            assertThat(reasons).containsExactly("NO_TRIP_COST");
            reasons = JsonPath.read(body, "$.rows[?(@.tripId == '" + openActualOnly + "')].reason");
            assertThat(reasons).containsExactly("NOT_CALCULABLE");
            List<String> own = JsonPath.read(body, "$.rows[?(@.tripId == '" + ownFleet + "')].tripId");
            assertThat(own).isEmpty();
        }

        @Test
        @DisplayName("the accounting date is the company's calendar day, not UTC's")
        void companyTimeZone() throws Exception {
            // 23:30 in Lima on 30 September is already 1 October in UTC.
            OffsetDateTime lateEvening = LocalDate.of(2026, 9, 30).atTime(23, 30).atZone(LIMA).toOffsetDateTime();
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "1200.00", lateEvening);

            mockMvc.perform(asAdmin(post(ACCRUALS + "/sweep?from=2026-09-30&to=2026-09-30"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created").value(1));
            String accrual = accrualOf(trip);
            assertThat(scalar("SELECT service_date::text FROM tms.freight_accrual WHERE id = '" + accrual + "'"))
                    .isEqualTo("2026-09-30");
            assertThat(scalar("SELECT period::text FROM tms.freight_accrual_entry WHERE accrual_id = '" + accrual
                    + "' AND entry_type = 'ACCRUE'")).isEqualTo("2026-09-01");
            // The person who asked is who created it.
            assertThat(scalar("SELECT created_by::text FROM tms.freight_accrual_entry WHERE accrual_id = '"
                    + accrual + "'")).isEqualTo(adminUserId);

            String list = mockMvc.perform(asAdmin(get(ACCRUALS + "?period=2026-09&carrierId=" + carrierA
                            + "&currency=PEN"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(list, "$.content[*].id");
            assertThat(ids).contains(accrual);
            mockMvc.perform(asAdmin(get(ACCRUALS + "?period=2026-10"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.id == '" + accrual + "')]").isEmpty());
        }

        @Test
        @DisplayName("a company accruing at delivery accrues completed trips only")
        void deliveredTrigger() throws Exception {
            String inTransit = dispatchedTrip(COMPANY_B, carrierB, cardB, "500.00", OffsetDateTime.now().minusHours(6));
            String completed = dispatchedTrip(COMPANY_B, carrierB, cardB, "650.00", OffsetDateTime.now().minusHours(8));
            execute("UPDATE tms.trip SET status = 'COMPLETED', actual_completion_at = now() - interval '1 hour',"
                    + " completed_by = '" + adminUserId + "' WHERE id = '" + completed + "'");

            accrualSweep.sweepAll();

            assertThat(count("SELECT count(*) FROM tms.freight_accrual WHERE trip_id = '" + inTransit + "'")).isZero();
            String accrual = accrualOf(completed);
            assertThat(scalar("SELECT source_type FROM tms.freight_accrual_entry WHERE accrual_id = '" + accrual + "'"))
                    .isEqualTo("TRIP_COMPLETED");
        }
    }

    // ------------------------------------------------------------------ immutability

    @Nested
    @DisplayName("the movements are append-only")
    class Immutability {

        @Test
        @DisplayName("UPDATE and DELETE fail for the runtime role (grant) and for the owner (trigger)")
        void appendOnly() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "400.00", OffsetDateTime.now().minusHours(4));
            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);

            assertThatThrownBy(() -> executeStrict("UPDATE tms.freight_accrual_entry SET amount = 1 WHERE accrual_id = '"
                    + accrual + "'")).hasMessageContaining("append-only");
            assertThatThrownBy(() -> executeStrict("DELETE FROM tms.freight_accrual_entry WHERE accrual_id = '"
                    + accrual + "'")).hasMessageContaining("append-only");

            String asRuntime = "SET ROLE tms_app; SELECT set_config('tms.company_id', '" + COMPANY_A + "', false); ";
            assertThatThrownBy(() -> executeStrict(asRuntime + "UPDATE tms.freight_accrual_entry SET reason = 'x'"
                    + " WHERE accrual_id = '" + accrual + "'")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> executeStrict(asRuntime + "DELETE FROM tms.freight_accrual_entry WHERE accrual_id = '"
                    + accrual + "'")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> executeStrict(asRuntime + "DELETE FROM tms.freight_accrual WHERE id = '"
                    + accrual + "'")).hasMessageContaining("permission denied");
            assertThat(entries(accrual)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ reports

    @Nested
    @DisplayName("reports and the journal")
    class Reports {

        @Test
        @DisplayName("aging puts the open balance in the bucket of its service date, per carrier and currency")
        void aging() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "333.00",
                    LocalDate.of(2026, 5, 15).atTime(10, 0).atZone(LIMA).toOffsetDateTime());
            mockMvc.perform(asAdmin(post(ACCRUALS + "/sweep?from=2026-05-15&to=2026-05-15"), COMPANY_A))
                    .andExpect(status().isOk());
            accrualOf(trip);

            // 2026-06-30 is 46 days after 2026-05-15: the 31-60 bucket. Before that, nothing.
            String body = mockMvc.perform(asViewer(get(ACCRUALS + "/aging?asOf=2026-06-30"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOf").value("2026-06-30"))
                    .andReturn().getResponse().getContentAsString();
            List<Double> bucket = JsonPath.read(body, "$.rows[?(@.currency == 'PEN')].days31To60");
            assertThat(bucket).containsExactly(333.00);
            mockMvc.perform(asViewer(get(ACCRUALS + "/aging?asOf=2026-05-14"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(0));
        }

        @Test
        @DisplayName("the journal books both sides with the mapped accounts and warns, never invents, when one is missing")
        void journal() throws Exception {
            String trip = dispatchedTrip(COMPANY_C, carrierC, cardC, "1000.00",
                    LocalDate.of(2026, 8, 15).atTime(9, 0).atZone(LIMA).toOffsetDateTime());
            mockMvc.perform(asAdmin(post(ACCRUALS + "/sweep?from=2026-08-01&to=2026-08-31"), COMPANY_C))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created").value(1));
            mockMvc.perform(asAdmin(post(GL), COMPANY_C).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concept\":\"FREIGHT_EXPENSE\",\"accountCode\":\"6311\",\"costCenterCode\":\"CC-10\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(get(ACCRUALS + "/journal?period=2026-08"), COMPANY_C))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period").value("2026-08"))
                    .andExpect(jsonPath("$.rows.length()").value(1))
                    .andExpect(jsonPath("$.rows[0].entryType").value("ACCRUE"))
                    .andExpect(jsonPath("$.rows[0].amount").value(1000.00))
                    .andExpect(jsonPath("$.rows[0].debitConcept").value("FREIGHT_EXPENSE"))
                    .andExpect(jsonPath("$.rows[0].debitAccount").value("6311"))
                    .andExpect(jsonPath("$.rows[0].debitCostCenter").value("CC-10"))
                    .andExpect(jsonPath("$.rows[0].creditConcept").value("FREIGHT_ACCRUAL"))
                    .andExpect(jsonPath("$.rows[0].creditAccount").doesNotExist())
                    .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("FREIGHT_ACCRUAL")));

            String csv = mockMvc.perform(asAdmin(get(ACCRUALS + "/journal?period=2026-08&format=CSV"), COMPANY_C))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Journal-Warnings", "1"))
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                    .andReturn().getResponse().getContentAsString();
            String[] lines = csv.split("\r\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).startsWith("period,entryId,entryDate,entryType");
            assertThat(lines[1]).startsWith("2026-08,").contains(",ACCRUE,TRIP_DISPATCHED," + trip + ",")
                    .contains(",PEN,1000.00,FREIGHT_EXPENSE,6311,CC-10,FREIGHT_ACCRUAL,,,");
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE company_id = '" + COMPANY_C
                    + "' AND action = 'ACCRUAL_JOURNAL_EXPORTED'")).isEqualTo(2);

            mockMvc.perform(asAdmin(get(ACCRUALS + "/journal?period=2026-13"), COMPANY_C))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GL mappings: one active per concept and scope, changed and retired with an audit trail")
        void glMappings() throws Exception {
            String body = mockMvc.perform(asAdmin(post(GL), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concept\":\"CARRIER_PAYABLE\",\"accountCode\":\"4212\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scopeType").value("COMPANY"))
                    .andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(body, "$.id");

            mockMvc.perform(asAdmin(post(GL), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concept\":\"CARRIER_PAYABLE\",\"accountCode\":\"4213\"}"))
                    .andExpect(status().isConflict());
            mockMvc.perform(asAdmin(post(GL), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concept\":\"CARRIER_PAYABLE\",\"scopeType\":\"ROUTE\",\"accountCode\":\"4213\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(put(GL + "/" + id), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concept\":\"CARRIER_PAYABLE\",\"accountCode\":\"4214\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountCode").value("4214"));
            mockMvc.perform(asAdmin(post(GL + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + id
                    + "' AND action = 'GL_MAPPING_CHANGED'")).isEqualTo(3);
            mockMvc.perform(asViewer(get(GL + "?includeInactive=true"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + id + "')].active").value(org.hamcrest.Matchers.contains(false)));
        }
    }

    // ------------------------------------------------------------------ authority

    @Nested
    @DisplayName("authority and isolation")
    class Authority {

        @Test
        @DisplayName("a viewer reads; exporting and reversing are administrators'; another company sees nothing")
        void permissions() throws Exception {
            String trip = dispatchedTrip(COMPANY_A, carrierA, cardA, "250.00", OffsetDateTime.now().minusHours(7));
            accrualSweep.sweepAll();
            String accrual = accrualOf(trip);

            mockMvc.perform(asViewer(get(ACCRUALS + "/" + accrual), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(get(ACCRUALS + "/journal?period=2026-10"), COMPANY_A))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asPlanner(post(ACCRUALS + "/" + accrual + "/reverse"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asPlanner(post(ACCRUALS + "/sweep?from=2026-10-01&to=2026-10-02"), COMPANY_A))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asViewer(post(GL), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concept\":\"FREIGHT_EXPENSE\",\"accountCode\":\"1\"}"))
                    .andExpect(status().isForbidden());

            // The same administrator, signed into another company, cannot reach it.
            mockMvc.perform(asAdmin(get(ACCRUALS + "/" + accrual), COMPANY_B)).andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(post(ACCRUALS + "/" + accrual + "/reverse"), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"cross\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(get(ACCRUALS), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.id == '" + accrual + "')]").isEmpty());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertBalanceIsTheSumOfItsMovements(String accrual) {
        String totals = scalar("SELECT (accrued_amount - consumed_amount - reversed_amount)::text FROM"
                + " tms.freight_accrual WHERE id = '" + accrual + "'");
        String sum = scalar("SELECT sum(amount)::text FROM tms.freight_accrual_entry WHERE accrual_id = '"
                + accrual + "'");
        assertThat(new BigDecimal(sum)).isEqualByComparingTo(new BigDecimal(totals));
    }

    /** Receives an invoice billing one trip, as the admin; returns its id. */
    private String invoice(UUID companyId, String carrierId, String trip, String chargeCode, String amount)
            throws Exception {
        String line = "{\"tripId\":\"" + trip + "\",\"description\":\"Line\""
                + (chargeCode == null ? "" : ",\"chargeCode\":\"" + chargeCode + "\"")
                + ",\"lineAmount\":" + amount + "}";
        String body = "{\"carrierId\":\"" + carrierId + "\",\"invoiceNumber\":\"ACR-" + SEQUENCE.incrementAndGet()
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

    private static String accrualOf(String trip) {
        return idOf("SELECT id FROM tms.freight_accrual WHERE trip_id = '" + trip + "'");
    }

    private static long entries(String accrual) {
        return count("SELECT count(*) FROM tms.freight_accrual_entry WHERE accrual_id = '" + accrual + "'");
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

    /** Our own truck, costed - and still not a freight payable. */
    private static String ownFleetTrip(UUID companyId, OffsetDateTime departedAt) {
        int n = SEQUENCE.incrementAndGet();
        String trip = executedTrip(companyId, n, vehicle(companyId, n, null), null, departedAt);
        execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, actual_amount,"
                + " actual_recorded_at) VALUES ('" + companyId + "', '" + trip + "', '" + departedAt.toLocalDate()
                + "', 'PEN', 999.00, now())");
        return trip;
    }

    private static String vehicle(UUID companyId, int n, String carrierId) {
        String vehicleType = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + n + "', 'Rigid', 10000, 40, 20)"
                + " RETURNING id");
        return idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate, carrier_id)"
                + " VALUES ('" + companyId + "', '" + vehicleType + "', 'V-" + n + "', 'ACR-" + n + "', "
                + (carrierId == null ? "NULL" : "'" + carrierId + "'") + ") RETURNING id");
    }

    private static String executedTrip(UUID companyId, int n, String vehicle, String carrierId,
            OffsetDateTime departedAt) {
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', 'O-" + n + "', 'Origin') RETURNING id");
        String date = departedAt.toLocalDate().toString();
        String run = idOf("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date,"
                + " status) VALUES ('" + companyId + "', 'PL-ACR-" + n + "', '" + origin + "', '" + date
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
            throw new IllegalStateException("could not read the accrual fixture", failed);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(scalar(sql));
    }

    private static void execute(String sql) {
        try {
            executeStrict(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the accrual fixture", failed);
        }
    }

    private static void executeStrict(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
