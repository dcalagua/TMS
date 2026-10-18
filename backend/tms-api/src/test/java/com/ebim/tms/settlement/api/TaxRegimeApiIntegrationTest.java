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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Tax regimes per country end to end (V66, ADR-027): an Ecuadorian company - PLAPASA's case,
 * Guayaquil, dollars - receives a carrier's factura at VAT 0% with the income-tax withholding, matches,
 * approves and exports it, the payable is net of the withholding and the ERP's payment records the
 * withholding's certificate; a VAT-withholding agent withholds both on one document; a company of a
 * country without a regime of its own (Colombia) works with no withholdings; the fiscal regime is
 * edited only with {@code settlement.tax:manage} and audited; and a withholding's figures cannot be
 * edited by anybody.
 *
 * <p>TMS receives these documents and checks their shape; it issues and sends nothing to the SRI.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TaxRegimeApiIntegrationTest.JwtDecoderOverride.class)
class TaxRegimeApiIntegrationTest {

    private static final String INVOICES = "/api/v1/settlement/invoices";
    private static final String PAYABLES = "/api/v1/settlement/payables";
    private static final String FISCAL = "/api/v1/settlement/fiscal-settings";
    private static final String PAYMENTS = "/integration/v1/payable-payments";

    private static final UUID ORGANIZATION = UUID.fromString("66666666-0000-4000-8000-000000000001");
    /** Ecuador by its default country, the deployment's EC defaults. */
    private static final UUID COMPANY_EC = UUID.fromString("66666666-0000-4000-8000-0000000000c1");
    /** Ecuador too, and the one whose regime the tests edit, so the others keep the defaults. */
    private static final UUID COMPANY_AGENT = UUID.fromString("66666666-0000-4000-8000-0000000000c2");
    /** Default country PE; set to Colombia through the API. */
    private static final UUID COMPANY_CO = UUID.fromString("66666666-0000-4000-8000-0000000000c3");
    private static final UUID ADMIN_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e1");
    private static final UUID CHECKER_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e2");
    private static final UUID PLANNER_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e3");

    /** Valid Ecuadorian RUCs, private companies (modulo 11, weights 4,3,2,7,6,5,4,3,2; see EcuadorRucTest). */
    private static final String RUC_EC = "0990004196001";
    private static final String RUC_AGENT_CARRIER = "1790000001001";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String adminUserId;
    private static String carrierEc;
    private static String cardEc;
    private static String carrierAgent;
    private static String cardAgent;
    private static String carrierCo;
    private static String cardCo;
    private static String erpToken;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String checkerToken;
    private String plannerToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_tax_regime_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'TAX-ORG', 'Tax Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_EC + "', '" + ORGANIZATION + "', 'TAX-EC', 'Plasticos EC', 'America/Guayaquil'), ('"
                + COMPANY_AGENT + "', '" + ORGANIZATION + "', 'TAX-AG', 'Agente EC', 'America/Guayaquil'), ('"
                + COMPANY_CO + "', '" + ORGANIZATION + "', 'TAX-CO', 'Empresa CO', 'America/Bogota')");
        execute("INSERT INTO tms.company_settings (company_id, default_country) VALUES ('" + COMPANY_EC
                + "', 'EC'), ('" + COMPANY_AGENT + "', 'EC')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'tax.admin@example.invalid', 'TAX Admin'), ('" + CHECKER_AUTH
                + "', 'tax.checker@example.invalid', 'TAX Checker'), ('" + PLANNER_AUTH
                + "', 'tax.planner@example.invalid', 'TAX Planner')");
        adminUserId = scalar("SELECT id FROM tms.app_user WHERE email = 'tax.admin@example.invalid'");
        for (UUID company : List.of(COMPANY_EC, COMPANY_AGENT, COMPANY_CO)) {
            membership("tax.admin@example.invalid", company, "COMPANY_ADMIN");
            membership("tax.checker@example.invalid", company, "COMPANY_ADMIN");
            membership("tax.planner@example.invalid", company, "PLANNER");
            execute("INSERT INTO tms.tolerance_policy (company_id, percentage) VALUES ('" + company + "', 60)");
        }
        carrierEc = carrier(COMPANY_EC, "TAX-C1", "RUC", RUC_EC);
        cardEc = rateCard(COMPANY_EC, "RC-TAX-1", carrierEc, "USD");
        carrierAgent = carrier(COMPANY_AGENT, "TAX-C2", "RUC", RUC_AGENT_CARRIER);
        cardAgent = rateCard(COMPANY_AGENT, "RC-TAX-2", carrierAgent, "USD");
        carrierCo = carrier(COMPANY_CO, "TAX-C3", "NIT", "900123456-7");
        cardCo = rateCard(COMPANY_CO, "RC-TAX-3", carrierCo, "COP");
        erpToken = client(COMPANY_EC, "ERP EC", "integration.payable-payment:write");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        checkerToken = TestJwts.validFor(CHECKER_AUTH);
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
    }

    @Test
    @DisplayName("Ecuador: the regime the form reads - IVA, USD, freight 0%, SRI codes, 13-digit RUC, IR 1% 310")
    void ecuadorRegime() throws Exception {
        mockMvc.perform(as(adminToken, get(FISCAL), COMPANY_EC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regime").value("EC"))
                .andExpect(jsonPath("$.country").value("EC"))
                .andExpect(jsonPath("$.fiscalCurrency").value("USD"))
                .andExpect(jsonPath("$.vatLabel").value("IVA"))
                .andExpect(jsonPath("$.vatRate").value(15.00))
                .andExpect(jsonPath("$.freightVatRate").value(0.00))
                .andExpect(jsonPath("$.allowedVatRates.length()").value(3))
                .andExpect(jsonPath("$.breakdownRequired").value(true))
                .andExpect(jsonPath("$.documentKinds[0].code").value("01"))
                .andExpect(jsonPath("$.documentKinds[1].kind").value("CREDIT_NOTE"))
                .andExpect(jsonPath("$.documentKinds[1].code").value("04"))
                .andExpect(jsonPath("$.documentKinds[2].code").value("05"))
                .andExpect(jsonPath("$.documentNumber.placeholder").value("001-001-000000123"))
                .andExpect(jsonPath("$.taxId.rule").value("EC_RUC"))
                .andExpect(jsonPath("$.taxId.length").value(13))
                .andExpect(jsonPath("$.electronicValidation").value("EXTERNAL_DEPENDENCY"))
                .andExpect(jsonPath("$.withholdings[0].kind").value("EC_INCOME_TAX_WITHHOLDING"))
                .andExpect(jsonPath("$.withholdings[0].enabled").value(true))
                .andExpect(jsonPath("$.withholdings[0].code").value("310"))
                .andExpect(jsonPath("$.withholdings[0].rate").value(1.00))
                .andExpect(jsonPath("$.withholdings[0].base").value("SUBTOTAL"))
                .andExpect(jsonPath("$.withholdings[1].kind").value("EC_VAT_WITHHOLDING"))
                .andExpect(jsonPath("$.withholdings[1].enabled").value(false))
                .andExpect(jsonPath("$.withholdings[1].rate").value(70.00));
    }

    @Test
    @DisplayName("Ecuador end to end: received at VAT 0% with IR 1%, approved, exported, net payable, ERP pays")
    void ecuadorEndToEnd() throws Exception {
        String trip = dispatchedTrip(COMPANY_EC, carrierEc, cardEc, "1000.00", "USD");
        String sequential = String.valueOf(100 + SEQUENCE.incrementAndGet());
        String body = ecBody(carrierEc, "001-001-" + sequential, RUC_EC, "1000.00", "0", "0.00", "1000.00", trip);

        String received = mockMvc.perform(as(adminToken, post(INVOICES), COMPANY_EC)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                // Normalised: the sequential is printed with nine digits.
                .andExpect(jsonPath("$.invoiceNumber").value("001-001-000000" + sequential))
                .andExpect(jsonPath("$.fiscal.fiscalCountry").value("EC"))
                .andExpect(jsonPath("$.fiscal.regime").value("EC"))
                .andExpect(jsonPath("$.fiscal.vatLabel").value("IVA"))
                .andExpect(jsonPath("$.fiscal.documentKind").value("INVOICE"))
                .andExpect(jsonPath("$.fiscal.fiscalDocumentCode").value("01"))
                .andExpect(jsonPath("$.fiscal.issuerTaxId").value(RUC_EC))
                .andExpect(jsonPath("$.fiscal.taxRate").value(0.00))
                .andExpect(jsonPath("$.fiscal.withholdings.length()").value(1))
                .andExpect(jsonPath("$.fiscal.withholdings[0].kind").value("EC_INCOME_TAX_WITHHOLDING"))
                .andExpect(jsonPath("$.fiscal.withholdings[0].code").value("310"))
                .andExpect(jsonPath("$.fiscal.withholdings[0].base").value("SUBTOTAL"))
                .andExpect(jsonPath("$.fiscal.withholdings[0].baseAmount").value(1000.00))
                .andExpect(jsonPath("$.fiscal.withholdings[0].amount").value(10.00))
                .andExpect(jsonPath("$.fiscal.withholdingTotalAmount").value(10.00))
                // Never "valid": nobody asked the SRI, and TMS sends it nothing.
                .andExpect(jsonPath("$.fiscal.electronicValidation").value("EXTERNAL_DEPENDENCY"))
                .andReturn().getResponse().getContentAsString();
        String invoice = JsonPath.read(received, "$.id");
        String number = JsonPath.read(received, "$.invoiceNumber");

        // The same document in another spelling is the same document.
        assertThat(statusOf(as(adminToken, post(INVOICES), COMPANY_EC).contentType(MediaType.APPLICATION_JSON)
                .content(ecBody(carrierEc, number, RUC_EC, "1000.00", "0", "0.00", "1000.00", trip)))).isEqualTo(409);

        // Compared by subtotal against the USD estimate, approved by a second person, exported.
        mockMvc.perform(as(adminToken, post(INVOICES + "/" + invoice + "/match"), COMPANY_EC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.match.invoicedAmount").value(1000.00));
        mockMvc.perform(as(checkerToken, post(INVOICES + "/" + invoice + "/approve"), COMPANY_EC)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(as(adminToken, post(INVOICES + "/" + invoice + "/export"), COMPANY_EC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value(Matchers.containsString("\"fiscalCountry\":\"EC\"")))
                .andExpect(jsonPath("$.payload").value(Matchers.containsString("\"documentNumber\":\"" + number + "\"")))
                .andExpect(jsonPath("$.payload").value(Matchers.containsString("\"kind\":\"EC_INCOME_TAX_WITHHOLDING\"")))
                .andExpect(jsonPath("$.payload").value(Matchers.containsString("\"withholdingTotalAmount\":\"10.00\"")));

        // The carrier is paid the net of the withholding, which the shipper deposits with the SRI.
        mockMvc.perform(as(adminToken, get(PAYABLES + "/" + invoice), COMPANY_EC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentKind").value("INVOICE"))
                .andExpect(jsonPath("$.fiscalDocumentCode").value("01"))
                .andExpect(jsonPath("$.netAmount").value(1000.00))
                .andExpect(jsonPath("$.withholdings[0].kind").value("EC_INCOME_TAX_WITHHOLDING"))
                .andExpect(jsonPath("$.withholdingTotalAmount").value(10.00))
                .andExpect(jsonPath("$.payableAmount").value(990.00))
                .andExpect(jsonPath("$.openBalance").value(990.00));

        // The ERP names the document as the SRI does. A withholding the document does not carry is a 400.
        String key = "{\"invoice\":{\"carrierTaxId\":\"" + RUC_EC + "\",\"documentType\":\"01\",\"documentNumber\":\""
                + number + "\"},\"paymentReference\":\"EC-PAY-" + sequential + "\",\"paidOn\":\"2026-11-01\","
                + "\"amount\":990.00,\"currency\":\"USD\",\"withholdings\":[{\"kind\":\"%s\",\"amount\":10.00,"
                + "\"certificateNumber\":\"001-001-000000045\",\"certificateDate\":\"2026-10-16\"}]}";
        assertThat(statusOf(erp(key.formatted("EC_VAT_WITHHOLDING")))).isEqualTo(400);
        mockMvc.perform(erp(key.formatted("EC_INCOME_TAX_WITHHOLDING")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECORDED"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.openBalance").value(0.00));
        assertThat(scalar("SELECT certificate_number || '/' || certificate_date FROM tms.carrier_invoice_withholding"
                + " WHERE carrier_invoice_id = '" + invoice + "'")).isEqualTo("001-001-000000045/2026-10-16");
    }

    @Test
    @DisplayName("Ecuador refuses a wrong RUC check digit, a rate the regime does not allow, a missing breakdown"
            + " and a Peruvian number")
    void ecuadorRefusals() throws Exception {
        String trip = dispatchedTrip(COMPANY_EC, carrierEc, cardEc, "500.00", "USD");
        String number = "002-001-" + SEQUENCE.incrementAndGet();
        assertThat(postStatus(COMPANY_EC, ecBody(carrierEc, number, "0990004197001", "500.00", "0", "0.00", "500.00",
                trip))).isEqualTo(400);
        // 12% was the general rate before 2024-04-01; it is not one the regime allows any more.
        assertThat(postStatus(COMPANY_EC, ecBody(carrierEc, number, RUC_EC, "500.00", "12", "60.00", "560.00",
                trip))).isEqualTo(400);
        assertThat(postStatus(COMPANY_EC, ecBody(carrierEc, number, RUC_EC, null, null, null, "500.00", trip)))
                .isEqualTo(400);
        assertThat(postStatus(COMPANY_EC, ecBody(carrierEc, "F001-" + SEQUENCE.incrementAndGet(), RUC_EC, "500.00",
                "0", "0.00", "500.00", trip))).isEqualTo(400);
        // Another carrier's RUC on this carrier's document: the payload is fine, the issuer is not.
        mockMvc.perform(as(adminToken, post(INVOICES), COMPANY_EC).contentType(MediaType.APPLICATION_JSON)
                        .content(ecBody(carrierEc, number, RUC_AGENT_CARRIER, "500.00", "0", "0.00", "500.00", trip)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("issuer-tax-id-mismatch")));
        // An accessory taxed at 15% is accepted.
        assertThat(postStatus(COMPANY_EC, ecBody(carrierEc, number, RUC_EC, "500.00", "15", "75.00", "575.00",
                trip))).isEqualTo(201);
    }

    @Test
    @DisplayName("a VAT-withholding agent: the regime is edited with settlement.tax:manage, audited, and both"
            + " withholdings apply to one taxed document")
    void vatWithholdingAgent() throws Exception {
        String agent = "{\"fiscalCountry\":null,\"vatRate\":null,\"freightVatRate\":null,\"withholdings\":["
                + "{\"kind\":\"EC_VAT_WITHHOLDING\",\"enabled\":true,\"code\":null,\"rate\":null,\"thresholdAmount\":null}]}";
        mockMvc.perform(as(plannerToken, put(FISCAL), COMPANY_AGENT).contentType(MediaType.APPLICATION_JSON)
                .content(agent)).andExpect(status().isForbidden());
        // A withholding of another country's regime is a 400.
        mockMvc.perform(as(adminToken, put(FISCAL), COMPANY_AGENT).contentType(MediaType.APPLICATION_JSON)
                        .content(agent.replace("EC_VAT_WITHHOLDING", "PE_DETRACTION")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(adminToken, put(FISCAL), COMPANY_AGENT).contentType(MediaType.APPLICATION_JSON)
                        .content(agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withholdings[1].kind").value("EC_VAT_WITHHOLDING"))
                .andExpect(jsonPath("$.withholdings[1].enabled").value(true))
                .andExpect(jsonPath("$.withholdings[1].rate").value(70.00))
                .andExpect(jsonPath("$.stored.withholdings[0].kind").value("EC_VAT_WITHHOLDING"))
                .andExpect(jsonPath("$.stored.withholdings[0].enabled").value(true));
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE company_id = '" + COMPANY_AGENT
                + "' AND aggregate_type = 'COMPANY' AND action = 'UPDATE' AND metadata::text LIKE '%FISCAL_REGIME%'"))
                .isEqualTo(1);

        String trip = dispatchedTrip(COMPANY_AGENT, carrierAgent, cardAgent, "1000.00", "USD");
        String received = mockMvc.perform(as(adminToken, post(INVOICES), COMPANY_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecBody(carrierAgent, "001-002-" + SEQUENCE.incrementAndGet(), RUC_AGENT_CARRIER,
                                "1000.00", "15", "150.00", "1150.00", trip)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fiscal.withholdings.length()").value(2))
                .andExpect(jsonPath("$.fiscal.withholdings[0].kind").value("EC_INCOME_TAX_WITHHOLDING"))
                .andExpect(jsonPath("$.fiscal.withholdings[0].amount").value(10.00))
                .andExpect(jsonPath("$.fiscal.withholdings[1].kind").value("EC_VAT_WITHHOLDING"))
                .andExpect(jsonPath("$.fiscal.withholdings[1].base").value("VAT"))
                .andExpect(jsonPath("$.fiscal.withholdings[1].baseAmount").value(150.00))
                .andExpect(jsonPath("$.fiscal.withholdings[1].amount").value(105.00))
                .andExpect(jsonPath("$.fiscal.withholdingTotalAmount").value(115.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(received, "$.id")).isNotBlank();

        // A carrier exempt from the IR withholding: overridden at receipt, with the reason, and audited.
        String exempt = mockMvc.perform(as(adminToken, post(INVOICES), COMPANY_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecBody(carrierAgent, "001-002-" + SEQUENCE.incrementAndGet(), RUC_AGENT_CARRIER,
                                "200.00", "0", "0.00", "200.00", null).replace(",\"lines\"",
                                ",\"withholdingOverrides\":[{\"kind\":\"EC_INCOME_TAX_WITHHOLDING\",\"amount\":0,"
                                        + "\"reason\":\"RIMPE negocio popular\"}],\"lines\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fiscal.withholdings[0].computedAmount").value(2.00))
                .andExpect(jsonPath("$.fiscal.withholdings[0].amount").value(0.00))
                .andExpect(jsonPath("$.fiscal.withholdings[0].overrideReason").value("RIMPE negocio popular"))
                .andReturn().getResponse().getContentAsString();
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + JsonPath.read(exempt, "$.id")
                + "' AND action = 'RETENTION_OVERRIDDEN' AND metadata::text LIKE '%EC_INCOME_TAX_WITHHOLDING%'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a country without a regime of its own: generic, any currency, free-text number, no withholdings")
    void genericCountry() throws Exception {
        mockMvc.perform(as(adminToken, put(FISCAL), COMPANY_CO).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiscalCountry\":\"co\",\"vatRate\":19,\"withholdings\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regime").value("GENERIC"))
                .andExpect(jsonPath("$.country").value("CO"))
                .andExpect(jsonPath("$.fiscalCurrency").doesNotExist())
                .andExpect(jsonPath("$.vatRate").value(19.00))
                .andExpect(jsonPath("$.taxId.rule").value("FREE_TEXT"))
                .andExpect(jsonPath("$.withholdings.length()").value(0));

        String trip = dispatchedTrip(COMPANY_CO, carrierCo, cardCo, "1000000.00", "COP");
        String body = "{\"carrierId\":\"" + carrierCo + "\",\"documentNumber\":\"FE-" + SEQUENCE.incrementAndGet()
                + "\",\"issuerTaxId\":\"900123456-7\",\"invoiceDate\":\"2026-10-15\",\"currency\":\"COP\","
                + "\"totalAmount\":1190000.00,\"subtotalAmount\":1000000.00,\"taxRate\":19,\"taxAmount\":190000.00,"
                + "\"lines\":[{\"tripId\":\"" + trip + "\",\"description\":\"Flete\",\"lineAmount\":1000000.00}]}";
        String invoice = JsonPath.read(mockMvc.perform(as(adminToken, post(INVOICES), COMPANY_CO)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fiscal.regime").value("GENERIC"))
                .andExpect(jsonPath("$.fiscal.fiscalDocumentCode").doesNotExist())
                .andExpect(jsonPath("$.fiscal.withholdings.length()").value(0))
                .andExpect(jsonPath("$.fiscal.withholdingTotalAmount").value(0.00))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // No breakdown is required either.
        assertThat(postStatus(COMPANY_CO, "{\"carrierId\":\"" + carrierCo + "\",\"invoiceNumber\":\"CO-"
                + SEQUENCE.incrementAndGet() + "\",\"invoiceDate\":\"2026-10-15\",\"currency\":\"USD\","
                + "\"totalAmount\":10.00,\"lines\":[{\"description\":\"Peaje\",\"lineAmount\":10.00}]}"))
                .isEqualTo(201);

        mockMvc.perform(as(adminToken, post(INVOICES + "/" + invoice + "/match"), COMPANY_CO))
                .andExpect(jsonPath("$.status").value("MATCHED"));
        mockMvc.perform(as(checkerToken, post(INVOICES + "/" + invoice + "/approve"), COMPANY_CO)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mockMvc.perform(as(adminToken, post(INVOICES + "/" + invoice + "/export"), COMPANY_CO))
                .andExpect(status().isOk());
        mockMvc.perform(as(adminToken, get(PAYABLES + "/" + invoice), COMPANY_CO))
                .andExpect(jsonPath("$.withholdingTotalAmount").value(0.00))
                .andExpect(jsonPath("$.payableAmount").value(1190000.00));
    }

    @Test
    @DisplayName("a withholding's figures are frozen for the owner (trigger) and it is never deleted by tms_app")
    void withholdingsAreUpdateProtected() throws Exception {
        String trip = dispatchedTrip(COMPANY_EC, carrierEc, cardEc, "300.00", "USD");
        String invoice = JsonPath.read(mockMvc.perform(as(adminToken, post(INVOICES), COMPANY_EC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecBody(carrierEc, "003-001-" + SEQUENCE.incrementAndGet(), RUC_EC, "300.00", "0",
                                "0.00", "300.00", trip)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String where = " WHERE carrier_invoice_id = '" + invoice + "'";

        assertThatThrownBy(() -> executeStrict("UPDATE tms.carrier_invoice_withholding SET amount = 0" + where))
                .hasMessageContaining("frozen");
        // The certificate is what may still be recorded.
        executeStrict("UPDATE tms.carrier_invoice_withholding SET certificate_number = 'C-1'" + where);
        String asRuntime = "SET ROLE tms_app; SELECT set_config('tms.company_id', '" + COMPANY_EC + "', false); ";
        assertThatThrownBy(() -> executeStrict(asRuntime + "DELETE FROM tms.carrier_invoice_withholding" + where))
                .hasMessageContaining("permission denied");
        assertThat(scalar("SELECT amount || '/' || certificate_number FROM tms.carrier_invoice_withholding" + where))
                .isEqualTo("3.00/C-1");
        // And another company's runtime transaction does not see it at all (ADR-005).
        assertThat(asRuntimeRole(COMPANY_CO, "SELECT count(*) FROM tms.carrier_invoice_withholding" + where))
                .isEqualTo("0");
        assertThat(asRuntimeRole(COMPANY_EC, "SELECT count(*) FROM tms.carrier_invoice_withholding" + where))
                .isEqualTo("1");
    }

    // ------------------------------------------------------------------ helpers

    private static String ecBody(String carrierId, String number, String ruc, String subtotal, String rate,
            String tax, String total, String trip) {
        String line = "{" + (trip == null ? "" : "\"tripId\":\"" + trip + "\",") + "\"description\":\"Flete\","
                + "\"lineAmount\":" + (subtotal == null ? total : subtotal) + "}";
        return "{\"carrierId\":\"" + carrierId + "\",\"documentNumber\":\"" + number + "\",\"issuerTaxId\":\"" + ruc
                + "\",\"invoiceDate\":\"2026-10-15\",\"currency\":\"USD\",\"totalAmount\":" + total
                + (subtotal == null ? "" : ",\"subtotalAmount\":" + subtotal + ",\"taxRate\":" + rate
                        + ",\"taxAmount\":" + tax)
                + ",\"lines\":[" + line + "]}";
    }

    private int postStatus(UUID company, String body) throws Exception {
        return statusOf(as(adminToken, post(INVOICES), company).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder erp(String body) {
        return post(PAYMENTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + erpToken)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "K-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder,
            UUID companyId) {
        return builder.header("Authorization", "Bearer " + token).header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String rateCard(UUID companyId, String code, String carrierId, String currency) {
        return scalar("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency,"
                + " base_amount, valid_from) VALUES ('" + companyId + "', '" + code + "', 'Card " + code
                + "', '" + carrierId + "', 'CARRIER', '" + currency + "', 100.00, '2026-01-01') RETURNING id");
    }

    private static String carrier(UUID companyId, String code, String taxIdType, String taxId) {
        return scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " SA', '" + taxIdType + "', '" + taxId
                + "') RETURNING id");
    }

    /** A trip that has left, on the carrier's vehicle, estimated at the amount it will be billed. */
    private static String dispatchedTrip(UUID companyId, String carrierId, String cardId, String expected,
            String currency) {
        int n = SEQUENCE.incrementAndGet();
        OffsetDateTime departedAt = OffsetDateTime.now().minusHours(6);
        String vehicleType = scalar("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + n + "', 'Rigid', 10000, 40, 20)"
                + " RETURNING id");
        String vehicle = scalar("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate, carrier_id)"
                + " VALUES ('" + companyId + "', '" + vehicleType + "', 'V-" + n + "', 'TAX-" + n + "', '" + carrierId
                + "') RETURNING id");
        String origin = scalar("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', 'O-" + n + "', 'Origin') RETURNING id");
        String date = departedAt.toLocalDate().toString();
        String run = scalar("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date,"
                + " status) VALUES ('" + companyId + "', 'PL-TAX-" + n + "', '" + origin + "', '" + date
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
                + "', '" + trip + "', '" + date + "', '" + currency + "', " + expected + ", now(), '" + cardId
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

    private static String client(UUID company, String name, String... scopes) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String rowId = scalar("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash) VALUES ('"
                + company + "', '" + clientId + "', '" + name + "', '" + IntegrationSecrets.hash(secret)
                + "') RETURNING id");
        for (String scope : scopes) {
            execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + rowId
                    + "', '" + scope + "')");
        }
        return IntegrationSecrets.toBearerToken(clientId, secret);
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
            throw new IllegalStateException("could not read the tax fixture", failed);
        }
    }

    private static String asRuntimeRole(UUID company, String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('tms.company_id', '" + company + "', true)");
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

    private static long count(String sql) {
        return Long.parseLong(scalar(sql));
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the tax fixture", failed);
        }
    }

    private static void executeStrict(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
