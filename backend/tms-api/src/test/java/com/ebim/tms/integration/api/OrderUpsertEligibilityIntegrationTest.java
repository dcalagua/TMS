package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The integration upsert's {@code markReadyForPlanning} under ADR-014 section 8: a machine cannot give
 * an override reason, so it releases only an order that needs none. Anything else stays
 * {@code NOT_READY}, and the result names the reasons in {@code releaseRefusedBy} - additively, so a
 * partner that never reads the field sees exactly the shape it always did.
 *
 * <p>{@code OrderUpsertReleaseIntegrationTest} pins the other half (an unchanged redelivery keeps the
 * release and the version) and is deliberately left untouched.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderUpsertEligibilityIntegrationTest {

    private static final String ORDERS = "/integration/v1/orders";

    private static final UUID ORGANIZATION = UUID.fromString("9e9e9e9e-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("9e9e9e9e-0000-4000-8000-0000000000c1");

    private static String jdbcUrl;
    private static String bearerToken;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_upsert_eligibility");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'OUE-ORG', 'Upsert Eligibility');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'OUE-A', 'Upsert Eligibility Company', 'America/Lima');
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION));
        String routedOrigin = insertLocation("OUE-CD", "ORIGIN");
        insertLocation("OUE-FREE", "ORIGIN");
        String store = insertLocation("OUE-ST", "DESTINATION");
        // Two active routes from OUE-CD both stop at OUE-ST: ambiguous, and never picked silently.
        for (String code : new String[] {"OUE-R1", "OUE-R2"}) {
            String route = insertReturningId("INSERT INTO tms.route (company_id, code, name, origin_id) VALUES ('"
                    + COMPANY + "', '" + code + "', '" + code + "', '" + routedOrigin + "')");
            execute("INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence) VALUES ('" + route
                    + "', '" + COMPANY + "', '" + store + "', 1)");
        }

        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String clientRowId = insertReturningId("INSERT INTO tms.integration_client (company_id, client_id, name,"
                + " secret_hash) VALUES ('" + COMPANY + "', '" + clientId + "', 'ERP OUE', '"
                + IntegrationSecrets.hash(secret) + "')");
        execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + clientRowId
                + "', 'integration.order:write')");
        bearerToken = IntegrationSecrets.toBearerToken(clientId, secret);
    }

    @Test
    @DisplayName("an order that needs no reason is released, the refusal list is empty, and the release is audited")
    void releasableOrderIsReleased() throws Exception {
        String reference = "OUE-" + UUID.randomUUID();

        upsert(body(reference, "OUE-FREE", "1200", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"))
                .andExpect(jsonPath("$.releaseRefusedBy", empty()));

        assertThat(queryString("SELECT count(*) FROM tms.audit_event a JOIN tms.transport_order o ON o.id ="
                + " a.aggregate_id WHERE o.external_reference = '" + reference + "' AND a.action = 'ORDER_RELEASED'"
                + " AND a.actor_machine_label IS NOT NULL")).isEqualTo("1");
    }

    @Test
    @DisplayName("no weight, volume or pallets: stays NOT_READY with MISSING_CAPACITY instead of failing the delivery")
    void missingCapacityStaysNotReady() throws Exception {
        upsert(body("OUE-" + UUID.randomUUID(), "OUE-FREE", "0", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.releaseRefusedBy", contains("MISSING_CAPACITY")));
    }

    @Test
    @DisplayName("an ambiguous route stays NOT_READY with ROUTE_AMBIGUOUS; the informative ROUTE_NOT_CONFIGURED is not named")
    void blockedRouteStaysNotReady() throws Exception {
        upsert(body("OUE-" + UUID.randomUUID(), "OUE-CD", "1200", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.releaseRefusedBy", contains("ROUTE_AMBIGUOUS")));
    }

    @Test
    @DisplayName("without markReadyForPlanning nothing is judged and nothing is refused")
    void noFlagNoJudgement() throws Exception {
        upsert(body("OUE-" + UUID.randomUUID(), "OUE-CD", "1200", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.releaseRefusedBy", empty()));
    }

    private ResultActions upsert(String body) throws Exception {
        return mockMvc.perform(post(ORDERS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(String reference, String originCode, String weightKg, boolean markReady) {
        return """
                {
                  "externalSource": "SAPB1_PE",
                  "externalReference": "%s",
                  "originCode": "%s",
                  "destinationCode": "OUE-ST",
                  "serviceDate": "2027-03-01",
                  "priority": "NORMAL",
                  "declaredWeightKg": %s,
                  "markReadyForPlanning": %s
                }
                """.formatted(reference, originCode, weightKg, markReady);
    }

    private static String insertLocation(String code, String role) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', '" + code + "', '" + code + "')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
        return id;
    }

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed an upsert fixture", failed);
        }
    }

    private static String queryString(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the upsert fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the upsert fixture", failed);
        }
    }
}
