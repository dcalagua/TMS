package com.ebim.tms.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Freight claims end to end (migration V71).
 *
 * <p>What no unit test can prove: a PARTIAL delivery with two refused chairs becomes a prefilled
 * claim; the four-eyes rule holds in the service <b>and</b> in the database; an accepted claim is
 * proposed as a PENALTY line on the carrier's invoice, applied only when the invoice is saved with
 * it, deducted once and never for more than was accepted; the claim is closed by the document that
 * recovered it; the incident centre lists exceptions and claims together; evidence goes through the
 * private store; and another company, or the carrier itself, sees none of it - in Java and in RLS.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FreightClaimApiIntegrationTest.JwtDecoderOverride.class)
class FreightClaimApiIntegrationTest {

    private static final String CLAIMS = "/api/v1/settlement/claims";
    private static final String INVOICES = "/api/v1/settlement/invoices";
    private static final String INCIDENTS = "/api/v1/incidents";

    private static final UUID ORGANIZATION = UUID.fromString("71717171-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("71717171-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("71717171-0000-4000-8000-0000000000c2");
    private static final UUID PLANNER_AUTH = UUID.fromString("71717171-0000-4000-8000-0000000000e1");
    private static final UUID ADMIN_AUTH = UUID.fromString("71717171-0000-4000-8000-0000000000e2");
    private static final UUID CHECKER_AUTH = UUID.fromString("71717171-0000-4000-8000-0000000000e3");
    private static final UUID VIEWER_AUTH = UUID.fromString("71717171-0000-4000-8000-0000000000e4");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String adminUserId;
    private static String carrierA;
    private static String carrierOther;
    private static String carrierB;
    private static String cardA;

    @Autowired
    private MockMvc mockMvc;

    private String plannerToken;
    private String adminToken;
    private String checkerToken;
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
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_freight_claim_api");
        seed();
        String root = Files.createTempDirectory("tms-claim-evidence").toAbsolutePath().toString();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("tms.storage.evidence.mode", () -> "LOCAL");
        registry.add("tms.storage.evidence.root", () -> root);
    }

    private static void seed() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'RC-ORG', 'Claims Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('" + COMPANY_A + "', '"
                + ORGANIZATION + "', 'RC-A', 'Company A', 'America/Lima'), ('" + COMPANY_B + "', '" + ORGANIZATION
                + "', 'RC-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + PLANNER_AUTH
                + "', 'rc.planner@example.invalid', 'RC Planner'), ('" + ADMIN_AUTH
                + "', 'rc.admin@example.invalid', 'RC Admin'), ('" + CHECKER_AUTH
                + "', 'rc.checker@example.invalid', 'RC Checker'), ('" + VIEWER_AUTH
                + "', 'rc.viewer@example.invalid', 'RC Viewer')");
        adminUserId = scalar("SELECT id FROM tms.app_user WHERE email = 'rc.admin@example.invalid'");
        membership("rc.planner@example.invalid", COMPANY_A, "PLANNER");
        membership("rc.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("rc.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("rc.checker@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("rc.viewer@example.invalid", COMPANY_A, "VIEWER");

        carrierA = scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + COMPANY_A + "', 'TRLIT', 'TRLIT Transportes', 'RUC', '" + validRuc("2071000001")
                + "') RETURNING id");
        carrierOther = scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + COMPANY_A + "', 'OTRO', 'Otro Transportista', 'RUC', '" + validRuc("2071000002")
                + "') RETURNING id");
        carrierB = scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + COMPANY_B + "', 'TRB', 'Carrier B', 'RUC', '" + validRuc("2071000003")
                + "') RETURNING id");
        cardA = scalar("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency, base_amount,"
                + " valid_from) VALUES ('" + COMPANY_A + "', 'RC-CARD', 'Card', '" + carrierA
                + "', 'CARRIER', 'PEN', 100.00, '2026-01-01') RETURNING id");
        execute("INSERT INTO tms.tolerance_policy (company_id, percentage) VALUES ('" + COMPANY_A + "', 60)");
    }

    @BeforeEach
    void mintTokens() {
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        checkerToken = TestJwts.validFor(CHECKER_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // ------------------------------------------------------------------ the acceptance criterion

    @Test
    @DisplayName("40 chairs, 2 broken: the PARTIAL delivery prefills RC-…, a second person accepts it, the"
            + " invoice of the trip is offered the penalty, deducts it once, and the claim closes on it")
    void partialDeliveryToPenalty() throws Exception {
        Shipment shipment = shipment(COMPANY_A, carrierA, "300.00");
        String delivery = partialDelivery(shipment, 40, 38, 2);

        // The draft reads what was recorded and writes nothing.
        as(plannerToken, COMPANY_A, get(CLAIMS + "/draft").param("orderDeliveryId", delivery))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimable").value(true))
                .andExpect(jsonPath("$.tripId").value(shipment.tripId))
                .andExpect(jsonPath("$.carrierId").value(carrierA))
                .andExpect(jsonPath("$.carrierName").value("TRLIT Transportes"))
                .andExpect(jsonPath("$.orderId").value(shipment.orderId))
                .andExpect(jsonPath("$.deliveryResult").value("PARTIAL"))
                .andExpect(jsonPath("$.suggestedType").value("SHORTAGE"))
                .andExpect(jsonPath("$.refusedLines.length()").value(1))
                .andExpect(jsonPath("$.refusedLines[0].orderLineId").value(shipment.orderLineId))
                .andExpect(jsonPath("$.refusedLines[0].quantity").value(2))
                .andExpect(jsonPath("$.refusedLines[0].materialCode").value("SILLA-01"));
        assertThat(count("SELECT count(*) FROM tms.freight_claim WHERE trip_id = '" + shipment.tripId + "'"))
                .isZero();

        // The planner opens it; the amount is the sum of its lines, and the carrier is the trip's.
        String body = "{\"tripId\":\"" + shipment.tripId + "\",\"orderDeliveryId\":\"" + delivery + "\","
                + "\"claimType\":\"DAMAGE\",\"incidentDate\":\"2026-10-20\",\"description\":\"2 sillas rotas\","
                + "\"currency\":\"pen\",\"lines\":[{\"orderLineId\":\"" + shipment.orderLineId + "\","
                + "\"description\":\"Silla rota\",\"quantity\":2,\"uom\":\"UND\",\"amount\":45.50}]}";
        String opened = as(plannerToken, COMPANY_A, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content(body).perform()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("OPEN"))
                .andExpect(jsonPath("$.summary.claimedAmount").value(45.50))
                .andExpect(jsonPath("$.summary.currency").value("PEN"))
                .andExpect(jsonPath("$.summary.carrierId").value(carrierA))
                .andExpect(jsonPath("$.summary.orderId").value(shipment.orderId))
                .andExpect(jsonPath("$.lines[0].orderLineNumber").value(1))
                .andExpect(jsonPath("$.openedByMe").value(true))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItems("SUBMIT", "UPDATE")))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(opened, "$.summary.id");
        String number = JsonPath.read(opened, "$.summary.claimNumber");
        assertThat(number).matches("RC-\\d{6}");

        as(plannerToken, COMPANY_A, post(CLAIMS + "/" + claim + "/submit")).perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("UNDER_REVIEW"));
        // Frozen once submitted.
        as(plannerToken, COMPANY_A, put(CLAIMS + "/" + claim)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimType\":\"DAMAGE\",\"incidentDate\":\"2026-10-20\",\"description\":\"x\","
                        + "\"claimedAmount\":10,\"currency\":\"PEN\"}")
                .perform().andExpect(status().isConflict());
        // A planner opens claims and does not decide them.
        decide(plannerToken, claim, "{\"decision\":\"ACCEPTED\",\"recoveryMethod\":\"PENALTY\"}")
                .andExpect(status().isForbidden());
        decide(checkerToken, claim, "{\"decision\":\"ACCEPTED\",\"recoveryMethod\":\"PENALTY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.summary.acceptedAmount").value(45.50))
                .andExpect(jsonPath("$.summary.recoveryMethod").value("PENALTY"));

        // Keying TRLIT's invoice: the penalty is proposed, not applied.
        as(adminToken, COMPANY_A, get(CLAIMS + "/penalty-suggestions").param("carrierId", carrierA)
                        .param("currency", "PEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].claimNumber").value(number))
                .andExpect(jsonPath("$[0].shipmentNumber").value(shipment.shipmentNumber))
                .andExpect(jsonPath("$[0].penaltyAmount").value(-45.50));
        as(adminToken, COMPANY_A, get(CLAIMS + "/penalty-suggestions").param("carrierId", carrierA)
                        .param("currency", "USD"))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(count("SELECT count(*) FROM tms.carrier_invoice_line WHERE penalty_reference_id = '" + claim + "'"))
                .isZero();

        // More than was accepted is refused; then the accepted amount is deducted.
        receive(shipment, "300.00", penaltyLine(shipment.tripId, claim, "-50.00")).andExpect(status().isBadRequest());
        String invoice = JsonPath.read(receive(shipment, "300.00", penaltyLine(shipment.tripId, claim, "-45.50"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Once.
        receive(shipment, "300.00", penaltyLine(shipment.tripId, claim, "-45.50")).andExpect(status().isConflict());
        as(adminToken, COMPANY_A, get(CLAIMS + "/penalty-suggestions").param("carrierId", carrierA)
                        .param("currency", "PEN"))
                .andExpect(jsonPath("$.length()").value(0));

        as(viewerToken, COMPANY_A, get(CLAIMS + "/" + claim)).perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.penalties.length()").value(1))
                .andExpect(jsonPath("$.penalties[0].invoiceId").value(invoice))
                .andExpect(jsonPath("$.penalties[0].amount").value(-45.50))
                .andExpect(jsonPath("$.openedByMe").value(false))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("CLOSE")));

        // Closed by the document that recovered it - and only by one that did.
        String unrelated = JsonPath.read(receive(shipment(COMPANY_A, carrierA, "100.00"), "100.00", null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        close(plannerToken, claim, "{\"settlementInvoiceId\":\"" + unrelated + "\"}").andExpect(status().isBadRequest());
        close(plannerToken, claim, "{}").andExpect(status().isBadRequest());
        close(plannerToken, claim, "{\"settlementInvoiceId\":\"" + invoice + "\",\"notes\":\"Descontado\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"))
                .andExpect(jsonPath("$.summary.resolution").value("ACCEPTED"))
                .andExpect(jsonPath("$.settlementInvoiceId").value(invoice))
                .andExpect(jsonPath("$.allowedActions.length()").value(0));

        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_type = 'FREIGHT_CLAIM'"
                + " AND aggregate_id = '" + claim + "' AND action IN ('CLAIM_OPENED', 'CLAIM_SUBMITTED',"
                + " 'CLAIM_ACCEPTED', 'CLAIM_CLOSED')")).isEqualTo(4);
    }

    // ------------------------------------------------------------------ four eyes

    @Test
    @DisplayName("whoever opened a claim cannot accept it - refused by the service and by the database")
    void fourEyes() throws Exception {
        Shipment shipment = shipment(COMPANY_A, carrierA, "200.00");
        String claim = openAndSubmit(adminToken, shipment, "80.00");

        decide(adminToken, claim, "{\"decision\":\"ACCEPTED\",\"recoveryMethod\":\"PENALTY\"}")
                .andExpect(status().isConflict());
        decide(adminToken, claim, "{\"decision\":\"PARTIALLY_ACCEPTED\",\"acceptedAmount\":40,"
                + "\"recoveryMethod\":\"CREDIT_NOTE\",\"reason\":\"Acuerdo\"}").andExpect(status().isConflict());
        // A partial agreement is less than claimed, and says why; an acceptance needs its recovery.
        decide(checkerToken, claim, "{\"decision\":\"PARTIALLY_ACCEPTED\",\"acceptedAmount\":80,"
                + "\"recoveryMethod\":\"CREDIT_NOTE\",\"reason\":\"Acuerdo\"}").andExpect(status().isBadRequest());
        decide(checkerToken, claim, "{\"decision\":\"PARTIALLY_ACCEPTED\",\"acceptedAmount\":40,"
                + "\"recoveryMethod\":\"CREDIT_NOTE\"}").andExpect(status().isBadRequest());
        decide(checkerToken, claim, "{\"decision\":\"ACCEPTED\"}").andExpect(status().isBadRequest());

        // The database holds the same rule against anything that bypasses Java.
        assertThatThrownBy(() -> executeStrict("UPDATE tms.freight_claim SET status = 'ACCEPTED',"
                + " resolution = 'ACCEPTED', accepted_amount = claimed_amount, recovery_method = 'PENALTY',"
                + " decided_by = opened_by, decided_at = now() WHERE id = '" + claim + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_freight_claim_resolution_terms");

        // Rejecting moves nothing, so the opener may; it says why, and is closed by no document.
        decide(adminToken, claim, "{\"decision\":\"REJECTED\"}").andExpect(status().isBadRequest());
        decide(adminToken, claim, "{\"decision\":\"REJECTED\",\"reason\":\"Embalaje del cliente\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("Embalaje del cliente"));
        close(adminToken, claim, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"))
                .andExpect(jsonPath("$.summary.resolution").value("REJECTED"));
    }

    @Test
    @DisplayName("a partial agreement recovered by credit note: no penalty is offered, and only the carrier's"
            + " credit note in the claim's currency closes it")
    void partialAgreementByCreditNote() throws Exception {
        Shipment shipment = shipment(COMPANY_A, carrierA, "500.00");
        String claim = openAndSubmit(plannerToken, shipment, "120.00");
        decide(checkerToken, claim, "{\"decision\":\"PARTIALLY_ACCEPTED\",\"acceptedAmount\":70,"
                + "\"recoveryMethod\":\"CREDIT_NOTE\",\"reason\":\"Se reconoce la mitad del daño\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("PARTIALLY_ACCEPTED"))
                .andExpect(jsonPath("$.summary.acceptedAmount").value(70.00));

        as(adminToken, COMPANY_A, get(CLAIMS + "/penalty-suggestions").param("carrierId", carrierA)
                        .param("currency", "PEN").param("tripId", shipment.tripId))
                .andExpect(jsonPath("$.length()").value(0));
        receive(shipment, "500.00", penaltyLine(shipment.tripId, claim, "-70.00")).andExpect(status().isBadRequest());

        String invoice = JsonPath.read(receive(shipment, "500.00", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        close(plannerToken, claim, "{\"settlementInvoiceId\":\"" + invoice + "\"}").andExpect(status().isBadRequest());
        String note = JsonPath.read(as(adminToken, COMPANY_A, post(INVOICES)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrierId\":\"" + carrierA + "\",\"invoiceNumber\":\"NC-" + SEQUENCE.incrementAndGet()
                        + "\",\"documentKind\":\"CREDIT_NOTE\",\"referencedInvoiceId\":\"" + invoice + "\","
                        + "\"invoiceDate\":\"2026-10-21\",\"currency\":\"PEN\",\"totalAmount\":70,\"subtotalAmount\":70,"
                        + "\"taxRate\":0,\"taxAmount\":0,\"lines\":[{\"tripId\":\"" + shipment.tripId
                        + "\",\"description\":\"Reclamo\",\"lineAmount\":70}]}")
                .perform().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        close(plannerToken, claim, "{\"settlementInvoiceId\":\"" + note + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"))
                .andExpect(jsonPath("$.summary.resolution").value("PARTIALLY_ACCEPTED"));
    }

    @Test
    @DisplayName("an own-fleet trip has nobody to claim from; an open claim is edited, and cancelled with a reason")
    void refusalsAndEdits() throws Exception {
        Shipment ownFleet = shipment(COMPANY_A, null, "100.00");
        as(plannerToken, COMPANY_A, get(CLAIMS + "/draft").param("tripId", ownFleet.tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimable").value(false));
        as(plannerToken, COMPANY_A, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content(openBody(ownFleet.tripId, "10.00")).perform().andExpect(status().isBadRequest());

        Shipment shipment = shipment(COMPANY_A, carrierA, "100.00");
        String claim = JsonPath.read(as(plannerToken, COMPANY_A, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content(openBody(shipment.tripId, "10.00")).perform().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.summary.id");
        // The amount of a claim with lines is their sum; a different figure is refused.
        as(plannerToken, COMPANY_A, put(CLAIMS + "/" + claim)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimType\":\"LOSS\",\"incidentDate\":\"2026-10-19\",\"description\":\"Caja perdida\","
                        + "\"claimedAmount\":99,\"currency\":\"PEN\",\"lines\":[{\"description\":\"Caja\",\"amount\":25}]}")
                .perform().andExpect(status().isBadRequest());
        as(plannerToken, COMPANY_A, put(CLAIMS + "/" + claim)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimType\":\"LOSS\",\"incidentDate\":\"2026-10-19\",\"description\":\"Caja perdida\","
                        + "\"currency\":\"PEN\",\"lines\":[{\"description\":\"Caja\",\"amount\":25},"
                        + "{\"description\":\"Flete\",\"amount\":5}]}")
                .perform().andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.claimType").value("LOSS"))
                .andExpect(jsonPath("$.summary.claimedAmount").value(30.00))
                .andExpect(jsonPath("$.lines.length()").value(2));
        // Rewritten whole, again: the old lines go first.
        as(plannerToken, COMPANY_A, put(CLAIMS + "/" + claim)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimType\":\"LOSS\",\"incidentDate\":\"2026-10-19\",\"description\":\"Caja perdida\","
                        + "\"currency\":\"PEN\",\"lines\":[{\"description\":\"Caja\",\"amount\":12}]}")
                .perform().andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));

        as(plannerToken, COMPANY_A, post(CLAIMS + "/" + claim + "/cancel")).contentType(MediaType.APPLICATION_JSON)
                .content("{}").perform().andExpect(status().isBadRequest());
        as(plannerToken, COMPANY_A, post(CLAIMS + "/" + claim + "/cancel")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Duplicado\"}").perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("Duplicado"));
        // A viewer reads and writes nothing.
        as(viewerToken, COMPANY_A, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content(openBody(shipment.tripId, "10.00")).perform().andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ the incident centre

    @Test
    @DisplayName("the incident centre lists the open exception and the claim opened from it, filtered and aged")
    void incidentCentre() throws Exception {
        Shipment shipment = shipment(COMPANY_A, carrierOther, "100.00");
        as(adminToken, COMPANY_A, post("/api/v1/planning/trips/" + shipment.tripId + "/exceptions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exceptionType\":\"TRAFFIC_DELAY\",\"notes\":\"Paro en la Panamericana\"}")
                .perform().andExpect(status().isOk());
        String exception = scalar("SELECT id FROM tms.trip_exception WHERE trip_id = '" + shipment.tripId + "'");

        as(plannerToken, COMPANY_A, get(CLAIMS + "/draft").param("tripExceptionId", exception))
                .andExpect(jsonPath("$.suggestedType").value("DELAY"))
                .andExpect(jsonPath("$.exceptionNotes").value("Paro en la Panamericana"));
        String opened = as(plannerToken, COMPANY_A, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tripId\":\"" + shipment.tripId + "\",\"tripExceptionId\":\"" + exception + "\","
                        + "\"claimType\":\"DELAY\",\"incidentDate\":\"2026-10-21\",\"description\":\"Llegó un día tarde\","
                        + "\"claimedAmount\":60,\"currency\":\"PEN\"}")
                .perform().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String number = JsonPath.read(opened, "$.summary.claimNumber");

        String page = as(viewerToken, COMPANY_A, get(INCIDENTS).param("carrierId", carrierOther)).perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.totals.openExceptions").value(1))
                .andExpect(jsonPath("$.totals.activeClaims").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> claimsOfException = JsonPath.read(page, "$.rows[?(@.kind == 'EXCEPTION')].claims[0]");
        assertThat(claimsOfException).containsExactly(number);
        List<String> carrierNames = JsonPath.read(page, "$.rows[*].carrierName");
        assertThat(carrierNames).containsOnly("Otro Transportista");

        as(viewerToken, COMPANY_A, get(INCIDENTS).param("carrierId", carrierOther).param("kind", "CLAIM"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].reference").value(number))
                .andExpect(jsonPath("$.rows[0].amount").value(60.00));
        as(viewerToken, COMPANY_A, get(INCIDENTS).param("carrierId", carrierOther).param("type", "TRAFFIC_DELAY"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].kind").value("EXCEPTION"))
                .andExpect(jsonPath("$.rows[0].ageDays").value(0));
        as(viewerToken, COMPANY_A, get(INCIDENTS).param("carrierId", carrierOther).param("status", "CLOSED"))
                .andExpect(jsonPath("$.rows.length()").value(0));
        as(viewerToken, COMPANY_A, get(INCIDENTS).param("type", "NONSENSE")).andExpect(status().isBadRequest());
        as(viewerToken, COMPANY_A, get(CLAIMS + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ------------------------------------------------------------------ evidence

    @Test
    @DisplayName("evidence goes to the private store, comes back through the API, and only from its own claim")
    void evidence() throws Exception {
        Shipment shipment = shipment(COMPANY_A, carrierA, "100.00");
        String claim = openAndSubmit(plannerToken, shipment, "15.00");
        String other = openAndSubmit(plannerToken, shipment(COMPANY_A, carrierA, "100.00"), "15.00");

        MockMultipartFile photo = new MockMultipartFile("file", "silla-rota.png", "image/png", "png-bytes".getBytes());
        String detail = mockMvc.perform(multipart(CLAIMS + "/" + claim + "/evidence").file(photo)
                        .param("description", "Foto en el muelle")
                        .header("Authorization", "Bearer " + plannerToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.evidence[0].originalFilename").value("silla-rota.png"))
                .andReturn().getResponse().getContentAsString();
        String evidence = JsonPath.read(detail, "$.evidence[0].id");

        as(viewerToken, COMPANY_A, get(CLAIMS + "/" + claim + "/evidence/" + evidence + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("png-bytes".getBytes()));
        as(viewerToken, COMPANY_A, get(CLAIMS + "/" + other + "/evidence/" + evidence + "/content"))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> asRuntimeRole(COMPANY_A, null,
                "DELETE FROM tms.freight_claim_evidence WHERE id = '" + evidence + "' RETURNING 1"))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("another company - and the carrier itself - sees none of a company's claims, in Java and in RLS")
    void tenantAndCarrierIsolation() throws Exception {
        Shipment shipment = shipment(COMPANY_A, carrierA, "100.00");
        String delivery = partialDelivery(shipment, 10, 9, 1);
        String claim = openAndSubmit(plannerToken, shipment, "9.00");

        as(adminToken, COMPANY_B, get(CLAIMS + "/" + claim)).perform().andExpect(status().isNotFound());
        as(adminToken, COMPANY_B, get(CLAIMS)).perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        as(adminToken, COMPANY_B, get(CLAIMS + "/draft").param("orderDeliveryId", delivery))
                .andExpect(status().isNotFound());
        as(adminToken, COMPANY_B, post(CLAIMS + "/" + claim + "/cancel")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}").perform().andExpect(status().isNotFound());
        // Company B cannot open a claim on company A's trip.
        as(adminToken, COMPANY_B, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content(openBody(shipment.tripId, "5.00")).perform().andExpect(status().isBadRequest());
        as(adminToken, COMPANY_B, get(INCIDENTS).param("status", "ALL")).perform()
                .andExpect(jsonPath("$.rows.length()").value(0));
        // Nor can company B's invoice deduct company A's claim.
        Shipment shipmentB = shipment(COMPANY_B, carrierB, "100.00");
        as(adminToken, COMPANY_B, get(CLAIMS + "/penalty-suggestions").param("carrierId", carrierA)
                        .param("currency", "PEN"))
                .andExpect(jsonPath("$.length()").value(0));

        String byId = "SELECT count(*) FROM tms.freight_claim WHERE id = '" + claim + "'";
        assertThat(asRuntimeRole(COMPANY_A, null, byId)).isEqualTo("1");
        assertThat(asRuntimeRole(COMPANY_B, null, byId)).isEqualTo("0");
        assertThat(asRuntimeRole(COMPANY_A, carrierA, byId)).isEqualTo("0");
        assertThat(asRuntimeRole(COMPANY_A, carrierA, "SELECT count(*) FROM tms.freight_claim_line l"
                + " WHERE l.freight_claim_id = '" + claim + "'")).isEqualTo("0");
        assertThatThrownBy(() -> asRuntimeRole(COMPANY_A, null, "DELETE FROM tms.freight_claim WHERE id = '" + claim
                + "' RETURNING 1")).hasRootCauseInstanceOf(SQLException.class);
        assertThat(shipmentB.tripId).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private record Shipment(String tripId, String shipmentNumber, String orderId, String orderLineId,
            String stopId) {
    }

    private String openAndSubmit(String token, Shipment shipment, String amount) throws Exception {
        String claim = JsonPath.read(as(token, COMPANY_A, post(CLAIMS)).contentType(MediaType.APPLICATION_JSON)
                .content(openBody(shipment.tripId, amount)).perform().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.summary.id");
        as(token, COMPANY_A, post(CLAIMS + "/" + claim + "/submit")).perform().andExpect(status().isOk());
        return claim;
    }

    private static String openBody(String tripId, String amount) {
        return "{\"tripId\":\"" + tripId + "\",\"claimType\":\"DAMAGE\",\"incidentDate\":\"2026-10-20\","
                + "\"description\":\"Mercadería dañada\",\"claimedAmount\":" + amount + ",\"currency\":\"PEN\"}";
    }

    private ResultActions decide(String token, String claim, String body) throws Exception {
        return as(token, COMPANY_A, post(CLAIMS + "/" + claim + "/decision")).contentType(MediaType.APPLICATION_JSON)
                .content(body).perform();
    }

    private ResultActions close(String token, String claim, String body) throws Exception {
        return as(token, COMPANY_A, post(CLAIMS + "/" + claim + "/close")).contentType(MediaType.APPLICATION_JSON)
                .content(body).perform();
    }

    private static String penaltyLine(String tripId, String claim, String amount) {
        return "{\"tripId\":\"" + tripId + "\",\"description\":\"Reclamo\",\"chargeCode\":\"PENALTY\","
                + "\"lineAmount\":" + amount + ",\"penaltyReason\":\"Reclamo de carga\","
                + "\"penaltyReferenceType\":\"FREIGHT_CLAIM\",\"penaltyReferenceId\":\"" + claim + "\"}";
    }

    /** A PEN invoice of the shipment's carrier, VAT-free: the freight and, when given, one more line. */
    private ResultActions receive(Shipment shipment, String freight, String extraLine) throws Exception {
        java.math.BigDecimal total = new java.math.BigDecimal(freight);
        if (extraLine != null) {
            total = total.add(new java.math.BigDecimal(JsonPath.<Object>read(extraLine, "$.lineAmount").toString()));
        }
        String carrier = scalar("SELECT carrier_id FROM tms.trip WHERE id = '" + shipment.tripId + "'");
        String lines = "{\"tripId\":\"" + shipment.tripId + "\",\"description\":\"Flete\",\"lineAmount\":" + freight + "}"
                + (extraLine == null ? "" : "," + extraLine);
        return as(adminToken, COMPANY_A, post(INVOICES)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrierId\":\"" + carrier + "\",\"invoiceNumber\":\"RC-F-" + SEQUENCE.incrementAndGet()
                        + "\",\"invoiceDate\":\"2026-10-21\",\"currency\":\"PEN\",\"totalAmount\":" + total.toPlainString()
                        + ",\"subtotalAmount\":" + total.toPlainString() + ",\"taxRate\":0,\"taxAmount\":0,"
                        + "\"lines\":[" + lines + "]}")
                .perform();
    }

    /** A trip that has left - on the carrier's vehicle, or with none for own fleet - with one stop and one order. */
    private static Shipment shipment(UUID companyId, String carrierId, String expected) {
        int n = SEQUENCE.incrementAndGet();
        OffsetDateTime departedAt = OffsetDateTime.now().minusHours(6);
        String date = departedAt.toLocalDate().toString();
        String origin = scalar("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', 'O-" + n + "', 'Origin') RETURNING id");
        String destination = scalar("INSERT INTO tms.location (company_id, code, name, country) VALUES ('" + companyId
                + "', 'D-" + n + "', 'Destination', 'EC') RETURNING id");
        String run = scalar("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date,"
                + " status) VALUES ('" + companyId + "', 'PL-RC-" + n + "', '" + origin + "', '" + date
                + "', 'DRAFT') RETURNING id");
        String departed = departedAt.toString();
        String before = departedAt.minusHours(1).toString();
        String vehicle = "NULL";
        if (carrierId != null) {
            String vehicleType = scalar("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                    + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + n
                    + "', 'Rigid', 10000, 40, 20) RETURNING id");
            vehicle = "'" + scalar("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate,"
                    + " carrier_id) VALUES ('" + companyId + "', '" + vehicleType + "', 'V-" + n + "', 'RC-" + n + "', '"
                    + carrierId + "') RETURNING id") + "'";
        } else {
            String vehicleType = scalar("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                    + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + n
                    + "', 'Rigid', 10000, 40, 20) RETURNING id");
            vehicle = "'" + scalar("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                    + " VALUES ('" + companyId + "', '" + vehicleType + "', 'V-" + n + "', 'RC-" + n + "') RETURNING id")
                    + "'";
        }
        String trip = scalar("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number,"
                + " carrier_id, vehicle_id, status, planned_departure_at, confirmed_at, actual_departure_at,"
                + " snapshot_max_weight_kg, snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at,"
                + " confirmed_by, dispatched_by, ready_at, ready_by, dispatch_source) VALUES ('" + companyId
                + "', '" + run + "', '" + date + "', 1, " + (carrierId == null ? "NULL" : "'" + carrierId + "'") + ", "
                + vehicle + ", 'IN_TRANSIT', '" + departed + "', '" + before + "', '" + departed + "', 10000, 40, 20, '"
                + before + "', '" + adminUserId + "', '" + adminUserId + "', '" + before + "', '" + adminUserId
                + "', 'OPERATOR') RETURNING id");
        if (carrierId != null && companyId.equals(COMPANY_A)) {
            execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, estimated_amount,"
                    + " estimated_at, rate_card_id, rate_card_code, rate_card_scope) VALUES ('" + companyId + "', '"
                    + trip + "', '" + date + "', 'PEN', " + expected + ", now(), '" + cardA + "', 'RC', 'CARRIER')");
        }
        String stop = scalar("INSERT INTO tms.trip_stop (trip_id, company_id, destination_id, sequence) VALUES ('"
                + trip + "', '" + companyId + "', '" + destination + "', 1) RETURNING id");
        String order = scalar("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date) VALUES ('" + companyId + "', 'TO-RC-" + n + "', '" + origin + "', '" + destination
                + "', '" + date + "') RETURNING id");
        String line = scalar("INSERT INTO tms.transport_order_line (order_id, line_number, material_code,"
                + " material_description, quantity, uom) VALUES ('" + order + "', 1, 'SILLA-01', 'Silla plástica',"
                + " 40, 'UND') RETURNING id");
        String shipmentNumber = scalar("SELECT shipment_number FROM tms.trip WHERE id = '" + trip + "'");
        return new Shipment(trip, shipmentNumber, order, line, stop);
    }

    /** What was recorded at the stop: a PARTIAL delivery of the order, with its line. */
    private static String partialDelivery(Shipment shipment, int attempted, int delivered, int refused) {
        String delivery = scalar("INSERT INTO tms.order_delivery (company_id, trip_id, trip_stop_id, order_id, result,"
                + " source, delivered_at, actor_app_user_id, notes) VALUES ('" + COMPANY_A + "', '" + shipment.tripId
                + "', '" + shipment.stopId + "', '" + shipment.orderId + "', 'PARTIAL', 'OPERATOR', now(), '"
                + adminUserId + "', 'Dos sillas rotas') RETURNING id");
        execute("INSERT INTO tms.order_delivery_line (company_id, order_delivery_id, order_id, order_line_id, uom,"
                + " quantity_attempted, quantity_delivered, quantity_refused, notes) VALUES ('" + COMPANY_A + "', '"
                + delivery + "', '" + shipment.orderId + "', '" + shipment.orderLineId + "', 'UND', " + attempted + ", "
                + delivered + ", " + refused + ", 'Rotas')");
        return delivery;
    }

    /** A valid RUC: the ten digits given and the module-11 check digit. */
    private static String validRuc(String firstTen) {
        int[] weights = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (firstTen.charAt(i) - '0') * weights[i];
        }
        int digit = 11 - (sum % 11);
        return firstTen + (digit == 10 ? 0 : digit == 11 ? 1 : digit);
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

    /** One query as the runtime role, in a company and - when given - as a carrier transaction. */
    private static String asRuntimeRole(UUID company, String carrier, String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('tms.company_id', '" + company + "', true), set_config("
                        + "'tms.carrier_id', '" + (carrier == null ? "" : carrier) + "', true)");
                statement.execute("SET LOCAL ROLE tms_app");
                try (ResultSet rows = statement.executeQuery(sql)) {
                    return rows.next() ? rows.getString(1) : null;
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not query as the runtime role", failed);
        }
    }

    /** A request builder with the caller's token and company, and a shortcut to perform it. */
    private Request as(String token, UUID companyId, MockHttpServletRequestBuilder builder) {
        return new Request(builder.header("Authorization", "Bearer " + token)
                .header(ApiHeaders.COMPANY_ID, companyId.toString()));
    }

    private final class Request {
        private final MockHttpServletRequestBuilder builder;

        Request(MockHttpServletRequestBuilder builder) {
            this.builder = builder;
        }

        Request contentType(MediaType type) {
            builder.contentType(type);
            return this;
        }

        Request content(String body) {
            builder.content(body);
            return this;
        }

        Request param(String name, String value) {
            builder.param(name, value);
            return this;
        }

        ResultActions perform() throws Exception {
            return mockMvc.perform(builder);
        }

        ResultActions andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            return perform().andExpect(matcher);
        }
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
            throw new IllegalStateException("could not read the claims fixture", failed);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(scalar(sql));
    }

    private static void execute(String sql) {
        try {
            executeStrict(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the claims fixture", failed);
        }
    }

    private static void executeStrict(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
