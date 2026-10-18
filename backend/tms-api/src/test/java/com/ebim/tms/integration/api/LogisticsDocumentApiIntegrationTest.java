package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import java.sql.SQLException;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

/** ADR-015: documents recorded from the ERP, linked to orders many to many, read per order. */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LogisticsDocumentApiIntegrationTest.JwtDecoderOverride.class)
class LogisticsDocumentApiIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c1");
    private static final UUID OTHER_COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e1");

    private static String jdbcUrl;
    private static String erpToken;
    private static String otherErpToken;
    private static String orderA;
    private static String orderB;
    private static String orderEc;

    /** Demo clave de acceso, 49 digits with a valid mod-11 digit: 15/10/2026, SRI 06, RUC 0992999999001, pruebas. */
    private static final String CLAVE = "1510202606099299999900110010010000001231234567819";

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
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_logistics_documents");
        seed();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seed() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'DOC-ORG', 'Doc Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'DOC-A', 'Doc Company', 'America/Lima'),
                    ('%s', '%s', 'DOC-B', 'Other Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('%s', 'doc@example.invalid', 'Doc');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'doc@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m JOIN tms.role r ON r.code = 'VIEWER'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, OTHER_COMPANY, ORGANIZATION, ADMIN_AUTH,
                ORGANIZATION, COMPANY, COMPANY));
        String origin = value("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'DOC-O', 'O') RETURNING id");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + origin + "', 'ORIGIN')");
        String destination = value("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'DOC-D', 'D') RETURNING id");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + destination + "', 'DESTINATION')");
        orderA = order(origin, destination, "PED-1");
        orderB = order(origin, destination, "PED-2");
        orderEc = order(origin, destination, "SO-2026-10040");
        order(origin, destination, "PED-PE");
        erpToken = client(COMPANY, "integration.document:write");
        otherErpToken = client(OTHER_COMPANY, "integration.document:write");
    }

    @Test
    @DisplayName("one invoice for two orders: 201 CREATED, visible on both; an unknown order is reported, not refused")
    void manyToMany() throws Exception {
        send(erpToken, body("INVOICE", "F001-00001234", "PED-1", "PED-2", "PED-404"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.linkedOrders").value(2))
                .andExpect(jsonPath("$.unknownOrders[0]").value("SAPB1_PE:PED-404"));

        mockMvc.perform(get("/api/v1/orders/" + orderA + "/documents")
                        .header("Authorization", "Bearer " + TestJwts.validFor(ADMIN_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentType").value("INVOICE"))
                .andExpect(jsonPath("$[0].documentNumber").value("F001-00001234"));
        assertThat(value("SELECT count(*) FROM tms.logistics_document_order lo JOIN tms.logistics_document d"
                + " ON d.id = lo.document_id WHERE d.document_number = 'F001-00001234'")).isEqualTo("2");
    }

    @Test
    @DisplayName("the same document again is UNCHANGED; a change is UPDATED and relinks; nothing moves an order")
    void idempotentUpsert() throws Exception {
        send(erpToken, body("GRE_REMITENTE", "T001-0000045", "PED-1")).andExpect(status().isCreated());
        send(erpToken, body("GRE_REMITENTE", "T001-0000045", "PED-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        send(erpToken, body("GRE_REMITENTE", "T001-0000045", "PED-2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPDATED"));

        assertThat(value("SELECT count(*) FROM tms.logistics_document_order lo JOIN tms.logistics_document d"
                + " ON d.id = lo.document_id WHERE d.document_number = 'T001-0000045' AND lo.order_id = '" + orderB
                + "'")).isEqualTo("1");
        assertThat(value("SELECT string_agg(DISTINCT status, ',') FROM tms.transport_order")).isEqualTo("READY_FOR_PLANNING");
    }

    @Test
    @DisplayName("another company's credential cannot link its document to this company's orders")
    void tenantIsolation() throws Exception {
        send(otherErpToken, body("INVOICE", "F002-1", "PED-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedOrders").value(0));
    }

    @Test
    @DisplayName("an unknown document type is 400")
    void validation() throws Exception {
        send(erpToken, body("RECEIPT", "X-1", "PED-1")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------
    // ADR-028 / V69: an international catalogue - Ecuador's SRI guia de remision (PLAPASA)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("EC guia de remision: 201 with every fiscal field and the recipients, read back on the order")
    void ecuadorianRemissionGuide() throws Exception {
        send(erpToken, "plapasa-guide-1", ecGuide("001-001-000000123", CLAVE, "Ferreteria El Constructor"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.linkedOrders").value(1));

        mockMvc.perform(get("/api/v1/orders/" + orderEc + "/documents")
                        .header("Authorization", "Bearer " + TestJwts.validFor(ADMIN_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentType").value("REMISSION_GUIDE"))
                .andExpect(jsonPath("$[0].documentNumber").value("001-001-000000123"))
                .andExpect(jsonPath("$[0].issuerTaxId").value("0992999999001"))
                .andExpect(jsonPath("$[0].fiscal.fiscalCountry").value("EC"))
                .andExpect(jsonPath("$[0].fiscal.fiscalDocumentCode").value("06"))
                .andExpect(jsonPath("$[0].fiscal.authorizationNumber").value(CLAVE))
                .andExpect(jsonPath("$[0].fiscal.transportStartDate").value("2026-10-15"))
                .andExpect(jsonPath("$[0].fiscal.transportEndDate").value("2026-10-16"))
                .andExpect(jsonPath("$[0].fiscal.carrierName").value("TRLIT Transportes del Litoral (demo)"))
                .andExpect(jsonPath("$[0].fiscal.vehiclePlate").value("GBC-5678"))
                .andExpect(jsonPath("$[0].fiscal.originAddress").value("Km 14,5 Via a Daule, Guayaquil"))
                .andExpect(jsonPath("$[0].recipients.length()").value(1))
                .andExpect(jsonPath("$[0].recipients[0].name").value("Ferreteria El Constructor"))
                .andExpect(jsonPath("$[0].recipients[0].relatedDocumentNumber").value("001-002-000004567"))
                .andExpect(jsonPath("$[0].recipients[0].items[0].description").value("Tubo PVC 110 mm x 3 m"))
                .andExpect(jsonPath("$[0].recipients[0].items[0].quantity").value(120));
    }

    @Test
    @DisplayName("EC: the same Idempotency-Key replays the first answer; a re-send without key is UNCHANGED")
    void ecuadorIdempotentResend() throws Exception {
        String body = ecGuide("001-001-000000124", CLAVE.replace("000000123", "000000124"), "Ferreteria Uno");
        String first = send(erpToken, "plapasa-guide-2", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = send(erpToken, "plapasa-guide-2", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        send(erpToken, null, body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        // The same key with other content is a conflict, not a second document.
        send(erpToken, "plapasa-guide-2", body.replace("Ferreteria Uno", "Ferreteria Dos"))
                .andExpect(status().isConflict());
        assertThat(value("SELECT count(*) FROM tms.logistics_document WHERE document_number = '001-001-000000124'"))
                .isEqualTo("1");
        assertThat(value("SELECT count(*) FROM tms.logistics_document_recipient r JOIN tms.logistics_document d"
                + " ON d.id = r.document_id WHERE d.document_number = '001-001-000000124'")).isEqualTo("1");
    }

    @Test
    @DisplayName("EC: a changed recipient list is UPDATED and replaces the stored one whole")
    void ecuadorRecipientsRewritten() throws Exception {
        String clave = CLAVE.replace("000000123", "000000125");
        send(erpToken, null, ecGuide("001-001-000000125", clave, "Ferreteria Tres")).andExpect(status().isCreated());
        send(erpToken, null, ecGuide("001-001-000000125", clave, "Ferreteria Cuatro"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPDATED"));
        assertThat(value("SELECT string_agg(r.name, ',') FROM tms.logistics_document_recipient r"
                + " JOIN tms.logistics_document d ON d.id = r.document_id"
                + " WHERE d.document_number = '001-001-000000125'")).isEqualTo("Ferreteria Cuatro");
    }

    @Test
    @DisplayName("EC: a clave that is not 49 digits, a number that is not 3-3-9 or a GRE alias is 400; nothing stored")
    void ecuadorShapeRefused() throws Exception {
        send(erpToken, null, ecGuide("001-001-000000126", CLAVE.substring(1), "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("49 digits")));
        send(erpToken, null, ecGuide("GR-126", CLAVE, "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("001-001-000000123")));
        send(erpToken, null, ecGuide("001-001-000000127", CLAVE, "X").replace("\"REMISSION_GUIDE\"",
                "\"GRE_REMITENTE\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("REMISSION_GUIDE")));
        send(erpToken, null, ecGuide("001-001-000000128", CLAVE, "X").replace("\"06\"", "\"01\""))
                .andExpect(status().isBadRequest());
        send(erpToken, null, ecGuide("001-001-000000129", CLAVE, "X").replace("\"EC\"", "\"ec\""))
                .andExpect(status().isBadRequest());
        assertThat(value("SELECT count(*) FROM tms.logistics_document WHERE document_number IN"
                + " ('001-001-000000126', 'GR-126', '001-001-000000127', '001-001-000000128', '001-001-000000129')"))
                .isEqualTo("0");
        assertThat(value("SELECT count(*) FROM tms.integration_request WHERE status = 'REJECTED'"
                + " AND error_summary LIKE '%49 digits%'")).isEqualTo("1");
    }

    @Test
    @DisplayName("Peru still works: GRE aliases with or without the new fields, a generic PE guide, numbers never parsed")
    void peruTypesStillWork() throws Exception {
        send(erpToken, null, body("GRE_TRANSPORTISTA", "V001-0000077", "PED-PE")).andExpect(status().isCreated());
        send(erpToken, null, body("GRE_REMITENTE", "T001-0000078", "PED-PE").replace("\"orders\"",
                "\"fiscalCountry\":\"PE\",\"fiscalDocumentCode\":\"09\",\"authorizationNumber\":\"hNz7Q2kT\","
                        + "\"orders\"")).andExpect(status().isCreated());
        send(erpToken, null, body("REMISSION_GUIDE", "T001-79", "PED-PE").replace("\"orders\"",
                "\"fiscalCountry\":\"PE\",\"fiscalDocumentCode\":\"31\",\"orders\"")).andExpect(status().isCreated());
        // A GRE stays SUNAT's: labelled with another country it is refused.
        send(erpToken, null, body("GRE_REMITENTE", "T001-0000080", "PED-PE").replace("\"orders\"",
                "\"fiscalCountry\":\"CO\",\"orders\"")).andExpect(status().isBadRequest());
        assertThat(value("SELECT count(*) FROM tms.logistics_document WHERE fiscal_country = 'PE'")).isEqualTo("2");
    }

    @Test
    @DisplayName("the database refuses what the API refuses: V69's CHECKs are the second line")
    void databaseChecks() {
        String insert = "INSERT INTO tms.logistics_document (company_id, source_system, document_type, document_number,"
                + " fiscal_country, authorization_number) VALUES ('" + COMPANY + "', 'SAP_EC', 'REMISSION_GUIDE', '%s',"
                + " 'EC', '%s')";
        assertThatThrownBy(() -> execute(insert.formatted("001-001-000000900", "123")))
                .rootCause().hasMessageContaining("ck_logistics_document_ec_authorization");
        assertThatThrownBy(() -> execute(insert.formatted("GR-900", CLAVE)))
                .rootCause().hasMessageContaining("ck_logistics_document_ec_number");
        assertThatThrownBy(() -> execute("INSERT INTO tms.logistics_document (company_id, source_system, document_type,"
                + " document_number, fiscal_country) VALUES ('" + COMPANY + "', 'SAP_EC', 'GRE_REMITENTE', 'T1-9', 'EC')"))
                .rootCause().hasMessageContaining("ck_logistics_document_gre_is_peru");
    }

    /** PLAPASA's guide (fake but well-formed numbers, clearly demo). */
    private static String ecGuide(String number, String clave, String recipientName) {
        return """
                {"sourceSystem":"SAP_EC","documentType":"REMISSION_GUIDE","documentNumber":"%s",
                 "issueDate":"2026-10-15","issuerTaxId":"0992999999001","status":"AUTORIZADO",
                 "fiscalCountry":"EC","fiscalDocumentCode":"06","authorizationNumber":"%s",
                 "transportStartDate":"2026-10-15","transportEndDate":"2026-10-16",
                 "carrierTaxId":"0991111111001","carrierName":"TRLIT Transportes del Litoral (demo)",
                 "vehiclePlate":"GBC-5678","originAddress":"Km 14,5 Via a Daule, Guayaquil",
                 "recipients":[{"taxId":"0990000000001","name":"%s",
                   "destinationAddress":"Av. Juan Tanca Marengo Km 2, Guayaquil","transferReason":"Venta",
                   "relatedDocumentCode":"01","relatedDocumentNumber":"001-002-000004567",
                   "relatedDocumentDate":"2026-10-14",
                   "items":[{"code":"PVC-110-3","description":"Tubo PVC 110 mm x 3 m","quantity":120.00,"unit":"UND"}]}],
                 "orders":[{"externalSource":"SAPB1_PE","externalReference":"SO-2026-10040"}]}
                """.formatted(number, clave, recipientName);
    }

    private ResultActions send(String token, String idempotencyKey, String body) throws Exception {
        var request = post("/integration/v1/logistics-documents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private ResultActions send(String token, String body) throws Exception {
        return mockMvc.perform(post("/integration/v1/logistics-documents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String type, String number, String... references) {
        StringBuilder orders = new StringBuilder();
        for (String reference : references) {
            if (!orders.isEmpty()) {
                orders.append(',');
            }
            orders.append("{\"externalSource\":\"SAPB1_PE\",\"externalReference\":\"").append(reference).append("\"}");
        }
        return "{\"sourceSystem\":\"SAPB1_PE\",\"documentType\":\"" + type + "\",\"documentNumber\":\"" + number
                + "\",\"issueDate\":\"2026-09-27\",\"amount\":1500.50,\"currency\":\"PEN\",\"status\":\"ISSUED\","
                + "\"orders\":[" + orders + "]}";
    }

    private static String order(String origin, String destination, String reference) {
        return value("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, status, total_pallets, external_source, external_reference) VALUES ('" + COMPANY
                + "', 'TO-DOC-" + reference + "', '" + origin + "', '" + destination
                + "', DATE '2026-10-01', 'READY_FOR_PLANNING', 5, 'SAPB1_PE', '" + reference + "') RETURNING id");
    }

    private static String client(UUID company, String scope) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String row = value("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash) VALUES ('"
                + company + "', '" + clientId + "', 'ERP', '" + IntegrationSecrets.hash(secret) + "') RETURNING id");
        execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + row + "', '"
                + scope + "')");
        return IntegrationSecrets.toBearerToken(clientId, secret);
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
