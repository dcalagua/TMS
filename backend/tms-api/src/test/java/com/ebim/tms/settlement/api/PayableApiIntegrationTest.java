package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.domain.IntegrationSecrets;
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
import org.hamcrest.Matchers;
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
 * Payable payment status end to end (migration V67, ADR-023 section 2, phase 65).
 *
 * <p>What no unit test can prove: the due date is frozen at approval under the right terms; the ERP's
 * payments move the invoice through partial to paid, refuse an overpayment and another currency with
 * 422 and are idempotent by key and by reference; a manual payment needs two people; the carrier
 * portal shows a carrier its own invoices and - in Java <b>and</b> in RLS - nobody else's; the webhooks
 * are written in the same transaction; and a recorded payment cannot be edited by anybody.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PayableApiIntegrationTest.JwtDecoderOverride.class)
class PayableApiIntegrationTest {

    private static final String INVOICES = "/api/v1/settlement/invoices";
    private static final String PAYABLES = "/api/v1/settlement/payables";
    private static final String PORTAL = "/api/v1/portal/invoices";
    private static final String PAYMENTS = "/integration/v1/payable-payments";

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    /** Has its own default payment terms (10 days). */
    private static final UUID COMPANY_B = UUID.fromString("77777777-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");
    private static final UUID CHECKER_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e2");
    private static final UUID VIEWER_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e3");
    private static final UUID CARRIER_ONE_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e4");
    private static final UUID CARRIER_TWO_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e5");

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String adminUserId;
    /** 45-day terms of its own, and a valid RUC so its documents can carry a CPE. */
    private static String carrierOne;
    private static String carrierOneRuc;
    /** No terms of its own: the property's 30 days. */
    private static String carrierTwo;
    /** Only the reports test bills it, so its aging rows are exactly what that test put there. */
    private static String carrierThree;
    private static String carrierB;
    private static String cardOne;
    private static String cardTwo;
    private static String cardThree;
    private static String cardB;
    private static String erpToken;
    private static String erpClientId;
    private static String erpBToken;
    private static String unscopedToken;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String checkerToken;
    private String viewerToken;
    private String carrierOneToken;
    private String carrierTwoToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_payable_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        // Webhooks on, so the invoice events can be seen reaching a subscription. Nothing is sent: the
        // poller is an hour away and the target does not resolve.
        registry.add("tms.integration.webhooks.secret-key", () -> "pay-test-webhook-key-0123456789abcdef");
        registry.add("tms.integration.webhooks.allow-insecure-targets", () -> "true");
        registry.add("tms.integration.webhooks.allow-private-network-targets", () -> "true");
        registry.add("tms.integration.webhooks.poll-interval", () -> "1h");
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'PAY-ORG', 'Payables Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'PAY-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'PAY-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'pay.admin@example.invalid', 'PAY Admin'), ('" + CHECKER_AUTH
                + "', 'pay.checker@example.invalid', 'PAY Checker'), ('" + VIEWER_AUTH
                + "', 'pay.viewer@example.invalid', 'PAY Viewer')");
        adminUserId = scalar("SELECT id FROM tms.app_user WHERE email = 'pay.admin@example.invalid'");
        membership("pay.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("pay.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("pay.checker@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("pay.checker@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("pay.viewer@example.invalid", COMPANY_A, "VIEWER");

        carrierOneRuc = validRuc("2060000001");
        carrierOne = carrier(COMPANY_A, "PAY-C1", carrierOneRuc);
        execute("UPDATE tms.carrier SET payment_terms_days = 45 WHERE id = '" + carrierOne + "'");
        carrierTwo = carrier(COMPANY_A, "PAY-C2", validRuc("2060000002"));
        carrierThree = carrier(COMPANY_A, "PAY-C3", validRuc("2060000003"));
        carrierB = carrier(COMPANY_B, "PAY-CB", validRuc("2060000004"));
        cardOne = rateCard(COMPANY_A, "RC-PAY-1", carrierOne);
        cardTwo = rateCard(COMPANY_A, "RC-PAY-2", carrierTwo);
        cardThree = rateCard(COMPANY_A, "RC-PAY-3", carrierThree);
        cardB = rateCard(COMPANY_B, "RC-PAY-B", carrierB);
        // A wide tolerance, so the invoices match and the tests reach approval without working
        // differences they are not about.
        execute("INSERT INTO tms.tolerance_policy (company_id, percentage) VALUES ('" + COMPANY_A + "', 60), ('"
                + COMPANY_B + "', 60)");
        execute("INSERT INTO tms.company_settings (company_id, default_payment_terms_days) VALUES ('" + COMPANY_B
                + "', 10)");

        carrierUser(CARRIER_ONE_AUTH, "pay.carrier1@example.invalid", carrierOne);
        carrierUser(CARRIER_TWO_AUTH, "pay.carrier2@example.invalid", carrierTwo);

        String[] erp = client(COMPANY_A, "ERP", "integration.payable-payment:write");
        erpToken = erp[0];
        erpClientId = erp[1];
        erpBToken = client(COMPANY_B, "ERP B", "integration.payable-payment:write")[0];
        unscopedToken = client(COMPANY_A, "ERP orders", "integration.order:write")[0];
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        checkerToken = TestJwts.validFor(CHECKER_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
        carrierOneToken = TestJwts.validFor(CARRIER_ONE_AUTH);
        carrierTwoToken = TestJwts.validFor(CARRIER_TWO_AUTH);
    }

    // ------------------------------------------------------------------ terms and due date

    @Nested
    @DisplayName("due date")
    class DueDate {

        @Test
        @DisplayName("frozen at approval: the carrier's terms, else the company's, else the property's 30 days")
        void termsChain() throws Exception {
            String one = approved(COMPANY_A, carrierOne, cardOne, "300.00", "2026-10-05");
            String two = approved(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05");
            String b = approved(COMPANY_B, carrierB, cardB, "300.00", "2026-10-05");

            mockMvc.perform(asAdmin(get(PAYABLES + "/" + one), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.dueDate").value("2026-11-19"))
                    .andExpect(jsonPath("$.paymentTermsDays").value(45));
            mockMvc.perform(asAdmin(get(PAYABLES + "/" + two), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.dueDate").value("2026-11-04"))
                    .andExpect(jsonPath("$.paymentTermsDays").value(30));
            mockMvc.perform(asAdmin(get(PAYABLES + "/" + b), COMPANY_B)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.dueDate").value("2026-10-15"));

            // New terms do not move what was already authorised.
            execute("UPDATE tms.carrier SET payment_terms_days = 5 WHERE id = '" + carrierTwo + "'");
            mockMvc.perform(asAdmin(get(INVOICES + "/" + two), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.dueDate").value("2026-11-04"))
                    .andExpect(jsonPath("$.paidAmount").value(0));
            execute("UPDATE tms.carrier SET payment_terms_days = NULL WHERE id = '" + carrierTwo + "'");
        }

        @Test
        @DisplayName("the carrier master edits its terms; outside 0..365 is a 400")
        void carrierMasterTerms() throws Exception {
            String body = "{\"code\":\"PAY-C2\",\"businessName\":\"PAY-C2 SA\",\"taxIdType\":\"RUC\",\"taxIdValue\":\""
                    + scalar("SELECT tax_id_value FROM tms.carrier WHERE id = '" + carrierTwo + "'")
                    + "\",\"paymentTermsDays\":%s}";
            mockMvc.perform(asAdmin(put("/api/v1/fleet/carriers/" + carrierTwo), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body.formatted("400")))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(put("/api/v1/fleet/carriers/" + carrierTwo), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body.formatted("60")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentTermsDays").value(60));
            mockMvc.perform(asAdmin(put("/api/v1/fleet/carriers/" + carrierTwo), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body.formatted("null")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentTermsDays").doesNotExist());
        }
    }

    // ------------------------------------------------------------------ the ERP channel

    @Nested
    @DisplayName("payments reported by the ERP")
    class Integration {

        @Test
        @DisplayName("partial then full: by CPE and by carrier code + number; detraction off the balance; PAYABLE_PAID")
        void partialThenPaid() throws Exception {
            String subscription = subscribe("PAYABLE_PAID");
            String cpe = "F001-" + SEQUENCE.incrementAndGet();
            String invoice = exported(COMPANY_A, carrierOne, cardOne, "1000.00", "2026-10-05", cpe);
            // 1000 in PEN above S/ 400: the 4 % detraction (40) is deposited elsewhere, so 960 is owed.
            mockMvc.perform(asAdmin(get(PAYABLES + "/" + invoice), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXPORTED"))
                    .andExpect(jsonPath("$.detractionAmount").value(40.00))
                    .andExpect(jsonPath("$.payableAmount").value(960.00))
                    .andExpect(jsonPath("$.openBalance").value(960.00));

            String[] cpeParts = cpe.split("-");
            payment(erpToken, "K-" + UUID.randomUUID(), "{\"invoice\":{\"carrierTaxId\":\"" + carrierOneRuc
                    + "\",\"documentType\":\"01\",\"series\":\"" + cpeParts[0] + "\",\"correlative\":\"000"
                    + cpeParts[1] + "\"},\"paymentReference\":\"TRX-1\",\"paidOn\":\"2026-11-01\",\"amount\":400.00,"
                    + "\"currency\":\"PEN\",\"sourceSystem\":\"SAP\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.outcome").value("RECORDED"))
                    .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                    .andExpect(jsonPath("$.paidAmount").value(400.00))
                    .andExpect(jsonPath("$.openBalance").value(560.00));

            String number = scalar("SELECT invoice_number FROM tms.carrier_invoice WHERE id = '" + invoice + "'");
            payment(erpToken, "K-" + UUID.randomUUID(), "{\"invoice\":{\"carrierCode\":\"pay-c1\",\"invoiceNumber\":\""
                    + number + "\"},\"paymentReference\":\"TRX-2\",\"paidOn\":\"2026-11-10\",\"amount\":560.00,"
                    + "\"currency\":\"pen\",\"detraction\":{\"constancyNumber\":\"DET-889\",\"constancyDate\":"
                    + "\"2026-11-09\",\"amount\":40.00}}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.openBalance").value(0.00));

            assertThat(scalar("SELECT status || '/' || paid_amount || '/' || detraction_constancy_number"
                    + " FROM tms.carrier_invoice WHERE id = '" + invoice + "'")).isEqualTo("PAID/960.00/DET-889");
            // The payments are the truth, and the running total agrees with them.
            assertThat(scalar("SELECT sum(amount) FROM tms.carrier_payment WHERE carrier_invoice_id = '" + invoice
                    + "'")).isEqualTo("960.00");
            assertThat(count("SELECT count(*) FROM tms.carrier_payment WHERE carrier_invoice_id = '" + invoice
                    + "' AND source = 'INTEGRATION' AND recorded_by_client = '" + erpClientId + "'")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + invoice
                    + "' AND action = 'PAYMENT_RECORDED' AND actor_machine_label IS NOT NULL")).isEqualTo(2);

            // The three invoice events, each once, in the business outbox; the subscribed one delivered.
            for (String event : List.of("INVOICE_APPROVED", "PAYABLE_EXPORTED", "PAYABLE_PAID")) {
                assertThat(count("SELECT count(*) FROM tms.business_outbox_event WHERE resource_id = '" + invoice
                        + "' AND resource_type = 'CARRIER_INVOICE' AND event_type = '" + event + "'"))
                        .as(event).isEqualTo(1);
            }
            String eventId = scalar("SELECT id FROM tms.business_outbox_event WHERE resource_id = '" + invoice
                    + "' AND event_type = 'PAYABLE_PAID'");
            String payload = scalar("SELECT payload FROM tms.webhook_delivery WHERE webhook_subscription_id = '"
                    + subscription + "' AND event_id = '" + eventId + "'");
            assertThat(payload).contains("\"type\":\"PAYABLE_PAID\"").contains("\"type\":\"carrier-invoice\"")
                    .contains("\"reference\":\"" + number + "\"");

            // Paid is the end: a further payment exceeds a zero balance.
            payment(erpToken, null, paymentBody(invoice, "TRX-3", "1.00", "PEN"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(Matchers.endsWith("payment-exceeds-balance")));
        }

        @Test
        @DisplayName("an overpayment and another currency are 422 with their reason, recorded as REJECTED")
        void refusals() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);

            payment(erpToken, "K-over-" + invoice, paymentBody(invoice, "OVER-1", "300.01", "PEN"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(Matchers.endsWith("payment-exceeds-balance")))
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("300.00")));
            payment(erpToken, null, paymentBody(invoice, "USD-1", "100.00", "USD"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(Matchers.endsWith("payment-currency-mismatch")));
            assertThat(count("SELECT count(*) FROM tms.integration_request WHERE integration_client_id = '"
                    + erpClientId + "' AND idempotency_key = 'K-over-" + invoice
                    + "' AND status = 'REJECTED' AND http_status = 422")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.carrier_payment WHERE carrier_invoice_id = '" + invoice + "'"))
                    .isZero();

            // Not exported yet: nothing to have paid.
            String onlyApproved = approved(COMPANY_A, carrierTwo, cardTwo, "200.00", "2026-10-05");
            payment(erpToken, null, paymentBody(onlyApproved, "EARLY-1", "50.00", "PEN"))
                    .andExpect(status().isConflict());
            // Unknown, another company's, ambiguous key, no scope.
            payment(erpToken, null, paymentBody(UUID.randomUUID().toString(), "X-1", "50.00", "PEN"))
                    .andExpect(status().isNotFound());
            payment(erpBToken, null, paymentBody(invoice, "X-2", "50.00", "PEN"))
                    .andExpect(status().isNotFound());
            payment(erpToken, null, "{\"invoice\":{\"invoiceId\":\"" + invoice + "\",\"carrierCode\":\"PAY-C2\","
                    + "\"invoiceNumber\":\"X\"},\"paymentReference\":\"X-3\",\"paidOn\":\"2026-11-01\","
                    + "\"amount\":50.00,\"currency\":\"PEN\"}")
                    .andExpect(status().isBadRequest());
            payment(unscopedToken, null, paymentBody(invoice, "X-4", "50.00", "PEN"))
                    .andExpect(status().isForbidden());
            payment(erpToken, null, paymentBody(invoice, "X-5", "-5.00", "PEN"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("idempotent: the same key replays; the same reference is DUPLICATE; other figures are 409")
        void idempotency() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            String body = paymentBody(invoice, "IDEM-1", "100.00", "PEN");

            String first = payment(erpToken, "K-idem-" + invoice, body).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String replay = payment(erpToken, "K-idem-" + invoice, body).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<String>read(replay, "$.paymentId")).isEqualTo(JsonPath.read(first, "$.paymentId"));
            payment(erpToken, "K-idem-" + invoice, paymentBody(invoice, "IDEM-1", "101.00", "PEN"))
                    .andExpect(status().isConflict());

            // A new key, the same payment: the business key answers.
            payment(erpToken, "K-other-" + invoice, body).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("DUPLICATE"))
                    .andExpect(jsonPath("$.paymentId").value(JsonPath.<String>read(first, "$.paymentId")));
            payment(erpToken, null, paymentBody(invoice, "IDEM-1", "150.00", "PEN"))
                    .andExpect(status().isConflict());

            assertThat(scalar("SELECT count(*) || '/' || sum(amount) FROM tms.carrier_payment"
                    + " WHERE carrier_invoice_id = '" + invoice + "'")).isEqualTo("1/100.00");
            assertThat(scalar("SELECT paid_amount FROM tms.carrier_invoice WHERE id = '" + invoice + "'"))
                    .isEqualTo("100.00");
        }

        @Test
        @DisplayName("a payment of a credit note is refused: the note adjusts its invoice's balance")
        void noteIsNotPaid() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            String note = receive(COMPANY_A, carrierTwo, null, "50.00", "2026-10-06", null,
                    ",\"documentType\":\"07\",\"referencedInvoiceId\":\"" + invoice + "\"");
            approve(note, COMPANY_A);
            mockMvc.perform(asAdmin(post(INVOICES + "/" + note + "/export"), COMPANY_A)).andExpect(status().isOk());

            payment(erpToken, null, paymentBody(note, "NOTE-1", "10.00", "PEN")).andExpect(status().isConflict());
            // The note nets into the invoice: 300 - 50 is what is owed now.
            mockMvc.perform(asAdmin(get(PAYABLES + "/" + invoice), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.netAmount").value(250.00))
                    .andExpect(jsonPath("$.openBalance").value(250.00));
            payment(erpToken, null, paymentBody(invoice, "NET-1", "250.01", "PEN"))
                    .andExpect(status().isUnprocessableEntity());
            payment(erpToken, null, paymentBody(invoice, "NET-1", "250.00", "PEN"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PAID"));
        }
    }

    // ------------------------------------------------------------------ the manual channel

    @Nested
    @DisplayName("a person keys, another confirms")
    class FourEyes {

        @Test
        @DisplayName("the requester cannot confirm; a second person can; a reference is keyed once")
        void makerAndChecker() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            String keyed = mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/payment-requests"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(manualBody("MAN-1", "120.00", "PEN")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("EXPORTED"))
                    .andExpect(jsonPath("$.requests[0].status").value("PENDING"))
                    .andReturn().getResponse().getContentAsString();
            String request = JsonPath.read(keyed, "$.requests[0].id");
            assertThat(count("SELECT count(*) FROM tms.carrier_payment WHERE carrier_invoice_id = '" + invoice + "'"))
                    .isZero();

            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/payment-requests"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(manualBody("MAN-1", "120.00", "PEN")))
                    .andExpect(status().isConflict());
            mockMvc.perform(asAdmin(get(PAYABLES + "/payment-requests"), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + request + "')].openBalance").value(300.00));

            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/payment-requests/" + request + "/confirm"),
                    COMPANY_A)).andExpect(status().isConflict());
            mockMvc.perform(asChecker(post(PAYABLES + "/" + invoice + "/payment-requests/" + request + "/confirm"),
                            COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                    .andExpect(jsonPath("$.openBalance").value(180.00))
                    .andExpect(jsonPath("$.payments[0].source").value("OPERATOR"))
                    .andExpect(jsonPath("$.requests[0].status").value("CONFIRMED"));
            assertThat(count("SELECT count(*) FROM tms.carrier_payment WHERE carrier_invoice_id = '" + invoice
                    + "' AND recorded_by = '" + adminUserId + "' AND confirmed_by <> recorded_by")).isEqualTo(1);
            mockMvc.perform(asChecker(post(PAYABLES + "/" + invoice + "/payment-requests/" + request + "/confirm"),
                    COMPANY_A)).andExpect(status().isConflict());

            // A second request above what is left is refused when keyed; a rejection says why.
            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/payment-requests"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(manualBody("MAN-2", "180.01", "PEN")))
                    .andExpect(status().isUnprocessableEntity());
            String second = JsonPath.read(mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice
                                    + "/payment-requests"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(manualBody("MAN-2", "80.00", "PEN")))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                    "$.requests[1].id");
            mockMvc.perform(asChecker(post(PAYABLES + "/" + invoice + "/payment-requests/" + second + "/reject"),
                            COMPANY_A).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\" \"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asChecker(post(PAYABLES + "/" + invoice + "/payment-requests/" + second + "/reject"),
                            COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"Not in the bank statement\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requests[1].status").value("REJECTED"))
                    .andExpect(jsonPath("$.paidAmount").value(120.00));
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + invoice
                    + "' AND action IN ('PAYMENT_REQUESTED', 'PAYMENT_RECORDED', 'PAYMENT_REQUEST_REJECTED')"))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("only payment:record keys and only payment:confirm confirms; a viewer reads")
        void permissions() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            mockMvc.perform(asViewer(get(PAYABLES + "/" + invoice), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(post(PAYABLES + "/" + invoice + "/payment-requests"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(manualBody("V-1", "10.00", "PEN")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asViewer(post(PAYABLES + "/" + invoice + "/hold"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
            // Another company does not see it.
            mockMvc.perform(asAdmin(get(PAYABLES + "/" + invoice), COMPANY_B)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("schedule, hold and the reports")
    class Lifecycle {

        // ------------------------------------------------------------------ schedule and hold

        @Test
        @DisplayName("schedule, hold (reason), no schedule while held, release (reason); an ERP payment ends a hold")
        void scheduleAndHold() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/schedule"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledPaymentDate\":\"2026-11-03\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.scheduledPaymentDate").value("2026-11-03"))
                    .andExpect(jsonPath("$.allowedActions").value(Matchers.containsInAnyOrder("SCHEDULE", "HOLD",
                            "REQUEST_PAYMENT")));
            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/hold"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/hold"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Damaged cargo claim\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ON_HOLD"))
                    .andExpect(jsonPath("$.onHoldReason").value("Damaged cargo claim"))
                    .andExpect(jsonPath("$.allowedActions").value(Matchers.containsInAnyOrder("RELEASE",
                            "REQUEST_PAYMENT")));
            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/schedule"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledPaymentDate\":\"2026-11-05\"}"))
                    .andExpect(status().isConflict());
            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/release"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Claim withdrawn\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.onHoldReason").doesNotExist());

            mockMvc.perform(asAdmin(post(PAYABLES + "/" + invoice + "/hold"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Second claim\"}"))
                    .andExpect(status().isOk());
            payment(erpToken, null, paymentBody(invoice, "HELD-1", "100.00", "PEN"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PARTIALLY_PAID"));
            assertThat(scalar("SELECT coalesce(on_hold_reason, '-') FROM tms.carrier_invoice WHERE id = '" + invoice + "'"))
                    .isEqualTo("-");
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + invoice
                    + "' AND action = 'PAYABLE_HOLD_RELEASED'")).isEqualTo(2);
        }

        // ------------------------------------------------------------------ reports

        @Test
        @DisplayName("aging, due list and the Control Tower card agree with the payable's own balance")
        void reports() throws Exception {
            LocalDate today = LocalDate.now(LIMA);
            // 30-day terms: an invoice dated 75 days ago fell due 45 days ago; one dated today is not due.
            String overdue = exported(COMPANY_A, carrierThree, cardThree, "300.00", today.minusDays(75).toString(), null);
            String current = exported(COMPANY_A, carrierThree, cardThree, "200.00", today.toString(), null);
            payment(erpToken, null, paymentBody(overdue, "AGE-1", "50.00", "PEN")).andExpect(status().isCreated());

            String aging = mockMvc.perform(asAdmin(get(PAYABLES + "/aging"), COMPANY_A)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String row = "$.carriers[?(@.carrierId == '" + carrierThree + "')]";
            assertThat(JsonPath.<List<Number>>read(aging, row + ".amounts.DAYS_31_60").get(0).doubleValue()).isEqualTo(250.0);
            assertThat(JsonPath.<List<Number>>read(aging, row + ".amounts.NOT_DUE").get(0).doubleValue()).isEqualTo(200.0);
            assertThat(JsonPath.<List<Number>>read(aging, row + ".count").get(0).intValue()).isEqualTo(2);

            String due = mockMvc.perform(asAdmin(get(PAYABLES + "/due").param("within", "0")
                            .param("carrierId", carrierThree), COMPANY_A))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(due, "$[*].invoiceId")).containsExactly(overdue);
            assertThat(JsonPath.<Integer>read(due, "$[0].daysToDue")).isEqualTo(-45);
            mockMvc.perform(asAdmin(get(PAYABLES + "/" + overdue), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.openBalance").value(JsonPath.<Number>read(due, "$[0].openBalance").doubleValue()))
                    .andExpect(jsonPath("$.daysOverdue").value(45));
            mockMvc.perform(asAdmin(get(PAYABLES + "/due").param("within", "400"), COMPANY_A))
                    .andExpect(status().isBadRequest());

            String summary = mockMvc.perform(asViewer(get(PAYABLES + "/summary"), COMPANY_A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.dueSoonDays").value(7))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Integer>>read(summary, "$.currencies[?(@.currency == 'PEN')].overdueCount").get(0))
                    .isGreaterThanOrEqualTo(1);
            // Another company's card is its own.
            mockMvc.perform(asAdmin(get(PAYABLES + "/due").param("within", "365"), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.invoiceId == '" + overdue + "')]").isEmpty());
            assertThat(current).isNotNull();
        }
    }

    // ------------------------------------------------------------------ the carrier portal

    @Nested
    @DisplayName("Mis facturas")
    class Portal {

        @Test
        @DisplayName("a carrier sees its own invoices with status, due date, paid and detraction - and no audit")
        void ownInvoices() throws Exception {
            String mine = exported(COMPANY_A, carrierOne, cardOne, "1000.00", "2026-10-05", null);
            payment(erpToken, null, paymentBody(mine, "PORTAL-1", "500.00", "PEN")).andExpect(status().isCreated());
            String theirs = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            String rejected = receive(COMPANY_A, carrierOne, null, "120.00", "2026-10-07", null, "");
            mockMvc.perform(asChecker(post(INVOICES + "/" + rejected + "/reject"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Wrong tariff\"}"))
                    .andExpect(status().isOk());

            String list = mockMvc.perform(asCarrier(get(PORTAL), carrierOneToken)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(list, "$[*].id")).contains(mine, rejected).doesNotContain(theirs);
            mockMvc.perform(asCarrier(get(PORTAL + "/" + mine), carrierOneToken)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                    .andExpect(jsonPath("$.dueDate").value("2026-11-19"))
                    .andExpect(jsonPath("$.paidAmount").value(500.00))
                    .andExpect(jsonPath("$.detractionAmount").value(40.00))
                    .andExpect(jsonPath("$.openBalance").value(460.00))
                    .andExpect(jsonPath("$.payments[0].paymentReference").value("PORTAL-1"))
                    .andExpect(jsonPath("$.lines[0].lineAmount").value(1000.00))
                    .andExpect(jsonPath("$.discrepancies").doesNotExist())
                    .andExpect(jsonPath("$.match").doesNotExist())
                    .andExpect(jsonPath("$.lines[0].expectedAmount").doesNotExist());
            mockMvc.perform(asCarrier(get(PORTAL + "/" + rejected), carrierOneToken)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.rejectionReason").value("Wrong tariff"))
                    .andExpect(jsonPath("$.openBalance").doesNotExist());

            // Another carrier's invoice answers as a missing one.
            mockMvc.perform(asCarrier(get(PORTAL + "/" + theirs), carrierOneToken)).andExpect(status().isNotFound());
            mockMvc.perform(asCarrier(get(PORTAL + "/" + mine), carrierTwoToken)).andExpect(status().isNotFound());
            // The portal is for carriers, and the office screens are not for carriers.
            mockMvc.perform(asAdmin(get(PORTAL), COMPANY_A)).andExpect(status().isForbidden());
            mockMvc.perform(asCarrier(get(PAYABLES + "/" + mine), carrierOneToken)).andExpect(status().isForbidden());
            mockMvc.perform(asCarrier(get(INVOICES + "/" + mine), carrierOneToken)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("RLS on its own keeps a carrier transaction out of another carrier's invoices and the audit")
        void rowLevelSecurity() throws Exception {
            String theirs = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            payment(erpToken, null, paymentBody(theirs, "RLS-1", "100.00", "PEN")).andExpect(status().isCreated());

            assertThat(asRuntimeRole(carrierOne, "SELECT count(*) FROM tms.carrier_invoice WHERE id = '" + theirs + "'"))
                    .isEqualTo("0");
            assertThat(asRuntimeRole(carrierOne, "SELECT count(*) FROM tms.carrier_invoice_line"
                    + " WHERE carrier_invoice_id = '" + theirs + "'")).isEqualTo("0");
            assertThat(asRuntimeRole(carrierOne, "SELECT count(*) FROM tms.carrier_payment"
                    + " WHERE carrier_invoice_id = '" + theirs + "'")).isEqualTo("0");
            assertThat(asRuntimeRole(carrierOne, "SELECT count(*) FROM tms.settlement_approval"
                    + " WHERE carrier_invoice_id = '" + theirs + "'")).isEqualTo("0");
            // The audit's internals are nobody's in a carrier transaction, not even the invoice's own carrier.
            assertThat(asRuntimeRole(carrierTwo, "SELECT count(*) FROM tms.carrier_invoice WHERE id = '" + theirs
                    + "'")).isEqualTo("1");
            assertThat(asRuntimeRole(carrierTwo, "SELECT count(*) FROM tms.freight_match WHERE carrier_invoice_id = '"
                    + theirs + "'")).isEqualTo("0");
            assertThat(asRuntimeRole(carrierTwo, "SELECT count(*) FROM tms.payable_export WHERE carrier_invoice_id = '"
                    + theirs + "'")).isEqualTo("0");
            // An office transaction sees all of it.
            assertThat(asRuntimeRole(null, "SELECT count(*) FROM tms.freight_match WHERE carrier_invoice_id = '"
                    + theirs + "'")).isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("payments are append-only")
    class Immutability {

        @Test
        @DisplayName("a recorded payment cannot be changed by the runtime role (grant) or by the owner (trigger)")
        void paymentsAreAppendOnly() throws Exception {
            String invoice = exported(COMPANY_A, carrierTwo, cardTwo, "300.00", "2026-10-05", null);
            payment(erpToken, null, paymentBody(invoice, "AO-1", "100.00", "PEN")).andExpect(status().isCreated());

            assertThatThrownBy(() -> executeStrict("UPDATE tms.carrier_payment SET amount = 1 WHERE carrier_invoice_id = '"
                    + invoice + "'")).hasMessageContaining("append-only");
            assertThatThrownBy(() -> executeStrict("DELETE FROM tms.carrier_payment WHERE carrier_invoice_id = '"
                    + invoice + "'")).hasMessageContaining("append-only");
            String asRuntime = "SET ROLE tms_app; SELECT set_config('tms.company_id', '" + COMPANY_A + "', false); ";
            assertThatThrownBy(() -> executeStrict(asRuntime + "UPDATE tms.carrier_payment SET amount = 1"
                    + " WHERE carrier_invoice_id = '" + invoice + "'")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> executeStrict(asRuntime + "DELETE FROM tms.carrier_payment_request"
                    + " WHERE carrier_invoice_id = '" + invoice + "'")).hasMessageContaining("permission denied");
            assertThat(scalar("SELECT amount FROM tms.carrier_payment WHERE carrier_invoice_id = '" + invoice + "'"))
                    .isEqualTo("100.00");
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions payment(String token, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(PAYMENTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private static String paymentBody(String invoiceId, String reference, String amount, String currency) {
        return "{\"invoice\":{\"invoiceId\":\"" + invoiceId + "\"},\"paymentReference\":\"" + reference
                + "\",\"paidOn\":\"2026-11-01\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
    }

    private static String manualBody(String reference, String amount, String currency) {
        return "{\"paymentReference\":\"" + reference + "\",\"paidOn\":\"2026-11-02\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\"}";
    }

    /** Received, matched, approved by the checker and exported by the admin. */
    private String exported(UUID companyId, String carrierId, String cardId, String amount, String invoiceDate,
            String cpe) throws Exception {
        String invoice = approved(companyId, carrierId, cardId, amount, invoiceDate, cpe);
        mockMvc.perform(asAdmin(post(INVOICES + "/" + invoice + "/export"), companyId))
                .andExpect(status().isOk());
        return invoice;
    }

    private String approved(UUID companyId, String carrierId, String cardId, String amount, String invoiceDate)
            throws Exception {
        return approved(companyId, carrierId, cardId, amount, invoiceDate, null);
    }

    private String approved(UUID companyId, String carrierId, String cardId, String amount, String invoiceDate,
            String cpe) throws Exception {
        String trip = dispatchedTrip(companyId, carrierId, cardId, amount);
        String invoice = receive(companyId, carrierId, trip, amount, invoiceDate, cpe, "");
        approve(invoice, companyId);
        return invoice;
    }

    /** Receives a PEN document billing one trip (or none), IGV-free so the figures stay round. */
    private String receive(UUID companyId, String carrierId, String trip, String amount, String invoiceDate,
            String cpe, String extra) throws Exception {
        String line = "{" + (trip == null ? "" : "\"tripId\":\"" + trip + "\",") + "\"description\":\"Freight\","
                + "\"lineAmount\":" + amount + "}";
        String identity = cpe == null
                ? "\"invoiceNumber\":\"PAY-" + SEQUENCE.incrementAndGet() + "\""
                : "\"series\":\"" + cpe.split("-")[0] + "\",\"correlative\":\"" + cpe.split("-")[1]
                        + "\",\"issuerRuc\":\"" + scalar("SELECT tax_id_value FROM tms.carrier WHERE id = '"
                        + carrierId + "'") + "\"";
        String body = "{\"carrierId\":\"" + carrierId + "\"," + identity + ",\"invoiceDate\":\"" + invoiceDate
                + "\",\"currency\":\"PEN\",\"totalAmount\":" + amount + ",\"subtotalAmount\":" + amount
                + ",\"igvRate\":0,\"igvAmount\":0" + extra + ",\"lines\":[" + line + "]}";
        String response = mockMvc.perform(asAdmin(post(INVOICES), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** Matches, accepts any difference, and has the checker approve. */
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

    private String subscribe(String eventType) throws Exception {
        String response = mockMvc.perform(asAdmin(post("/api/v1/webhooks"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ERP " + SEQUENCE.incrementAndGet() + "\","
                                + "\"targetUrl\":\"http://erp.example.invalid/tms\",\"eventTypes\":[\"" + eventType
                                + "\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.subscription.id");
    }

    /** A valid RUC: the ten digits given and the module-11 check digit (V66's {@code RucValidator}). */
    private static String validRuc(String firstTen) {
        int[] weights = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (firstTen.charAt(i) - '0') * weights[i];
        }
        int digit = 11 - (sum % 11);
        return firstTen + (digit == 10 ? 0 : digit == 11 ? 1 : digit);
    }

    private static String rateCard(UUID companyId, String code, String carrierId) {
        return scalar("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency,"
                + " base_amount, valid_from) VALUES ('" + companyId + "', '" + code + "', 'Card " + code
                + "', '" + carrierId + "', 'CARRIER', 'PEN', 100.00, '2026-01-01') RETURNING id");
    }

    private static String carrier(UUID companyId, String code, String ruc) {
        return scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " SA', 'RUC', '" + ruc + "') RETURNING id");
    }

    /** A trip that has left, on the carrier's vehicle, estimated at the amount it will be billed. */
    private static String dispatchedTrip(UUID companyId, String carrierId, String cardId, String expected) {
        int n = SEQUENCE.incrementAndGet();
        OffsetDateTime departedAt = OffsetDateTime.now().minusHours(6);
        String vehicleType = scalar("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + n + "', 'Rigid', 10000, 40, 20)"
                + " RETURNING id");
        String vehicle = scalar("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate, carrier_id)"
                + " VALUES ('" + companyId + "', '" + vehicleType + "', 'V-" + n + "', 'PAY-" + n + "', '" + carrierId
                + "') RETURNING id");
        String origin = scalar("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', 'O-" + n + "', 'Origin') RETURNING id");
        String date = departedAt.toLocalDate().toString();
        String run = scalar("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date,"
                + " status) VALUES ('" + companyId + "', 'PL-PAY-" + n + "', '" + origin + "', '" + date
                + "', 'DRAFT') RETURNING id");
        String departed = departedAt.toString();
        String before = departedAt.minusHours(1).toString();
        String trip = scalar("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number,"
                + " carrier_id, vehicle_id, status, planned_departure_at, confirmed_at, actual_departure_at,"
                + " snapshot_max_weight_kg, snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at,"
                + " confirmed_by, dispatched_by, ready_at, ready_by, dispatch_source) VALUES ('" + companyId
                + "', '" + run + "', '" + date + "', 1, '" + carrierId + "', '" + vehicle + "', 'IN_TRANSIT', '"
                + departed + "', '" + before + "', '" + departed + "', 10000, 40, 20, '" + before + "', '"
                + adminUserId + "', '" + adminUserId + "', '" + before + "', '" + adminUserId
                + "', 'OPERATOR') RETURNING id");
        execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, estimated_amount,"
                + " estimated_at, rate_card_id, rate_card_code, rate_card_scope) VALUES ('" + companyId
                + "', '" + trip + "', '" + date + "', 'PEN', " + expected + ", now(), '" + cardId
                + "', 'RC', 'CARRIER')");
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

    private static void carrierUser(UUID auth, String email, String carrier) {
        execute("""
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('%1$s', '%2$s', '%2$s');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id, carrier_id)
                SELECT id, '%3$s', '%4$s', '%5$s' FROM tms.app_user WHERE email = '%2$s';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%2$s'
                JOIN tms.role r ON r.code = 'CARRIER_OPERATOR';
                """.formatted(auth, email, ORGANIZATION, COMPANY_A, carrier));
    }

    private static String[] client(UUID company, String name, String... scopes) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String rowId = scalar("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash) VALUES ('"
                + company + "', '" + clientId + "', '" + name + "', '" + IntegrationSecrets.hash(secret)
                + "') RETURNING id");
        for (String scope : scopes) {
            execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + rowId
                    + "', '" + scope + "')");
        }
        return new String[] {IntegrationSecrets.toBearerToken(clientId, secret), rowId};
    }

    /** One query as the runtime role in company A, as a carrier transaction when one is given. */
    private static String asRuntimeRole(String carrier, String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('tms.company_id', '" + COMPANY_A + "', true), set_config("
                        + "'tms.carrier_id', '" + (carrier == null ? "" : carrier) + "', true)");
                statement.execute("SET LOCAL ROLE tms_app");
                try (ResultSet rows = statement.executeQuery(sql)) {
                    rows.next();
                    return rows.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not query as the runtime role", failed);
        }
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
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

    private MockHttpServletRequestBuilder asCarrier(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token)
                .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString());
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
            throw new IllegalStateException("could not read the payable fixture", failed);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(scalar(sql));
    }

    private static void execute(String sql) {
        try {
            executeStrict(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the payable fixture", failed);
        }
    }

    private static void executeStrict(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
