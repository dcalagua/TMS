package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
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
 * An ERP that re-sends an order it already sent must not take it back out of planning.
 *
 * <p>ADR-014 makes {@code READY_FOR_PLANNING} the one persisted meaning of "released", and release
 * a deliberate human act. Every edit of an order resets it to {@code NOT_READY}
 * ({@code TransportOrder.applyChanges}), which is right for a real change - the release was for the
 * order as it was - and would be wrong for a redelivery: an ERP replaying a week of traffic after
 * an outage would silently un-release every order a planner had released in that week.
 *
 * <p>{@code OrderIntakeService} answers a byte-for-byte redelivery with {@code UNCHANGED} without
 * writing. This test holds that behaviour to the release rule end to end: real credential, real
 * security chain, real PostgreSQL, a released order, and the same payload sent again with and
 * without {@code markReadyForPlanning}. The last test pins the other half - a relevant change does
 * un-release - so that the pair documents the rule rather than only one side of it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderUpsertReleaseIntegrationTest {

    private static final String ORDERS = "/integration/v1/orders";

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c1");

    private static String jdbcUrl;
    private static String bearerToken;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_upsert_release");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'OUR-ORG', 'Upsert Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'OUR-A', 'Upsert Company', 'America/Lima');
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION));
        insertLocation("OUR-CD", "ORIGIN");
        insertLocation("OUR-ST", "DESTINATION");

        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String clientRowId = insertReturningId("INSERT INTO tms.integration_client (company_id, client_id, name,"
                + " secret_hash) VALUES ('" + COMPANY + "', '" + clientId + "', 'ERP SAPB1_PE', '"
                + IntegrationSecrets.hash(secret) + "')");
        execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + clientRowId
                + "', 'integration.order:write')");
        bearerToken = IntegrationSecrets.toBearerToken(clientId, secret);
    }

    @Test
    @DisplayName("a released order re-sent unchanged, without markReadyForPlanning, stays released")
    void unchangedRedeliveryWithoutTheFlagKeepsTheRelease() throws Exception {
        String reference = "PED-" + UUID.randomUUID();
        upsert(body(reference, "NORMAL", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));
        long versionAfterRelease = version(reference);

        upsert(body(reference, "NORMAL", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));

        assertThat(orderStatus(reference)).isEqualTo("READY_FOR_PLANNING");
        // Not rewritten at all: the version a planner's screen holds is still current.
        assertThat(version(reference)).isEqualTo(versionAfterRelease);
    }

    @Test
    @DisplayName("a released order re-sent unchanged with markReadyForPlanning stays released and is not re-released")
    void unchangedRedeliveryWithTheFlagIsANoOp() throws Exception {
        String reference = "PED-" + UUID.randomUUID();
        upsert(body(reference, "NORMAL", true)).andExpect(status().isCreated());
        long versionAfterRelease = version(reference);

        upsert(body(reference, "NORMAL", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));

        assertThat(version(reference)).isEqualTo(versionAfterRelease);
    }

    @Test
    @DisplayName("a relevant change from the ERP does un-release the order, so the release is judged again")
    void aRelevantChangeUnreleases() throws Exception {
        String reference = "PED-" + UUID.randomUUID();
        upsert(body(reference, "NORMAL", true)).andExpect(status().isCreated());

        upsert(body(reference, "URGENT", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("NOT_READY"));

        assertThat(orderStatus(reference)).isEqualTo("NOT_READY");
    }

    private ResultActions upsert(String body) throws Exception {
        return mockMvc.perform(post(ORDERS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(String reference, String priority, boolean markReady) {
        return """
                {
                  "externalSource": "SAPB1_PE",
                  "externalReference": "%s",
                  "originCode": "OUR-CD",
                  "destinationCode": "OUR-ST",
                  "serviceDate": "2026-10-05",
                  "priority": "%s",
                  "declaredWeightKg": 1530.0,
                  "declaredVolumeM3": 4.2,
                  "declaredPallets": 3,
                  "lines": [
                    {"materialCode": "SKU-100", "materialDescription": "Cemento 42.5 kg", "quantity": 36,
                     "uom": "BOL", "unitWeightKg": 42.5}
                  ],
                  "markReadyForPlanning": %s
                }
                """.formatted(reference, priority, markReady);
    }

    private static String orderStatus(String reference) {
        return queryString("SELECT status FROM tms.transport_order WHERE company_id = '" + COMPANY
                + "' AND external_source = 'SAPB1_PE' AND external_reference = '" + reference + "'");
    }

    private static long version(String reference) {
        return Long.parseLong(queryString("SELECT version FROM tms.transport_order WHERE company_id = '" + COMPANY
                + "' AND external_source = 'SAPB1_PE' AND external_reference = '" + reference + "'"));
    }

    private static void insertLocation(String code, String role) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', '" + code + "', '" + code + "')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
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
