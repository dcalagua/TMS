package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
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
