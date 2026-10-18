package com.ebim.tms.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * ADR-022 section 2: a material's warehouse profile is copied onto an order line when the line is
 * written; the ERP's own line block wins as a whole (D-02); and a later profile change rewrites
 * nothing - not on a redelivery, not on an edit from the order form.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderLineMaterialSnapshotIntegrationTest.JwtDecoderOverride.class)
class OrderLineMaterialSnapshotIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c1");
    private static final UUID PLANNER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e1");

    private static String jdbcUrl;
    private static String erpToken;
    private static String originId;
    private static String destinationId;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_line_material_snapshot");
        seed();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seed() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'SNP-ORG', 'Snapshot Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'SNP-A', 'Snapshot Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('%s', 'snp@example.invalid', 'Snp');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'snp@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m JOIN tms.role r ON r.code = 'PLANNER'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, PLANNER_AUTH, ORGANIZATION, COMPANY, COMPANY));
        originId = location("SNP-CD", "ORIGIN");
        destinationId = location("SNP-ST", "DESTINATION");

        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String row = value("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash) VALUES ('"
                + COMPANY + "', '" + clientId + "', 'EWM+ERP', '" + IntegrationSecrets.hash(secret) + "') RETURNING id");
        execute("INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES ('" + row
                + "', 'integration.order:write'), ('" + row + "', 'integration.material:write')");
        erpToken = IntegrationSecrets.toBearerToken(clientId, secret);
    }

    @Test
    @DisplayName("a line without physical fields copies the profile, with its version, and takes its unit weight")
    void profileIsCopied() throws Exception {
        String material = "PLP-571-" + UUID.randomUUID();
        profile(material, "2026-10-18T10:00:00Z", "0.175").andExpect(status().isCreated());
        String reference = "PED-" + UUID.randomUUID();

        order(reference, line(material, "UN", "")).andExpect(status().isCreated());

        assertThat(lineRow(reference)).isEqualTo("PROFILE|0|0.1050|0.1750|NESTABLE|0.0120|100|false|0.012");
        assertThat(value("SELECT total_weight_kg FROM tms.transport_order WHERE external_reference = '" + reference
                + "'")).isEqualTo("12.000");
    }

    @Test
    @DisplayName("the ERP's line block wins as a whole: no field is merged in from the profile")
    void erpLineBlockWins() throws Exception {
        String material = "PLP-1601-" + UUID.randomUUID();
        profile(material, "2026-10-18T10:00:00Z", "0.175").andExpect(status().isCreated());
        String reference = "PED-" + UUID.randomUUID();

        order(reference, line(material, "UN", ", \"heightM\": 0.29, \"stackMode\": \"STACKABLE\""))
                .andExpect(status().isCreated());

        assertThat(lineRow(reference)).isEqualTo("ORDER_LINE|-|-|0.2900|STACKABLE|-|-|false|0.012");
    }

    @Test
    @DisplayName("a later profile change rewrites nothing: not a redelivery, not an edit from the order form")
    void laterProfileChangeDoesNotRewrite() throws Exception {
        String material = "BALDE-" + UUID.randomUUID();
        profile(material, "2026-10-18T10:00:00Z", "0.175").andExpect(status().isCreated());
        String reference = "PED-" + UUID.randomUUID();
        order(reference, line(material, "UN", "")).andExpect(status().isCreated());

        profile(material, "2026-10-18T11:00:00Z", "0.300")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

        order(reference, line(material, "UN", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        assertThat(lineRow(reference)).startsWith("PROFILE|0|0.1050|0.1750|");

        String orderId = value("SELECT id FROM tms.transport_order WHERE external_reference = '" + reference + "'");
        String version = value("SELECT version FROM tms.transport_order WHERE id = '" + orderId + "'");
        mockMvc.perform(put("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + TestJwts.validFor(PLANNER_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSource": "SAPB1_PE", "externalReference": "%s",
                                 "originId": "%s", "destinationId": "%s", "serviceDate": "2026-10-21",
                                 "priority": "URGENT", "version": %s,
                                 "lines": [{"materialCode": "%s", "materialDescription": "Material",
                                            "quantity": 1000, "uom": "UN", "unitWeightKg": 0.012}]}
                                """.formatted(reference, originId, destinationId, version, material)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].physicalSource").value("PROFILE"))
                .andExpect(jsonPath("$.lines[0].materialProfileVersion").value(0))
                .andExpect(jsonPath("$.lines[0].heightM").value(0.175));
        assertThat(lineRow(reference)).startsWith("PROFILE|0|0.1050|0.1750|");

        // A new order is the moment a new copy is taken.
        String fresh = "PED-" + UUID.randomUUID();
        order(fresh, line(material, "UN", "")).andExpect(status().isCreated());
        assertThat(lineRow(fresh)).startsWith("PROFILE|1|0.1050|0.3000|");
    }

    @Test
    @DisplayName("no unit weight is filled when the order declares one, nor on a line counted in kilograms")
    void unitFiguresAreFilledOnlyWhenSafe() throws Exception {
        String material = "RESINA-" + UUID.randomUUID();
        profile(material, "2026-10-18T10:00:00Z", "0.175").andExpect(status().isCreated());

        String declared = "PED-" + UUID.randomUUID();
        order(declared, line(material, "UN", ""), ", \"declaredWeightKg\": 13.5").andExpect(status().isCreated());
        assertThat(lineRow(declared)).endsWith("|-");
        assertThat(value("SELECT total_weight_kg FROM tms.transport_order WHERE external_reference = '" + declared
                + "'")).isEqualTo("13.500");

        String kilograms = "PED-" + UUID.randomUUID();
        order(kilograms, line(material, "KG", "")).andExpect(status().isCreated());
        assertThat(lineRow(kilograms)).startsWith("PROFILE|0|").endsWith("|-");
    }

    @Test
    @DisplayName("an invalid line block is 400: NESTABLE without nestIncrementM")
    void invalidLineBlockIs400() throws Exception {
        order("PED-" + UUID.randomUUID(), line("ANY-" + UUID.randomUUID(), "UN",
                ", \"heightM\": 0.2, \"stackMode\": \"NESTABLE\"")).andExpect(status().isBadRequest());
    }

    private ResultActions profile(String material, String sourceUpdatedAt, String heightM) throws Exception {
        return mockMvc.perform(put("/integration/v1/material-profiles/" + material)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + erpToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "%s", "lengthM": 0.105, "widthM": 0.105,
                         "heightM": %s, "unitWeightKg": 0.012, "stackMode": "NESTABLE", "stackMaxQuantity": 100,
                         "nestIncrementM": 0.012}
                        """.formatted(sourceUpdatedAt, heightM)));
    }

    private ResultActions order(String reference, String line) throws Exception {
        return order(reference, line, "");
    }

    private ResultActions order(String reference, String line, String extraHeader) throws Exception {
        return mockMvc.perform(post("/integration/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + erpToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalSource": "SAPB1_PE", "externalReference": "%s", "originCode": "SNP-CD",
                         "destinationCode": "SNP-ST", "serviceDate": "2026-10-20", "priority": "NORMAL"%s,
                         "lines": [%s]}
                        """.formatted(reference, extraHeader, line)));
    }

    private static String line(String material, String uom, String physical) {
        return "{\"materialCode\": \"" + material + "\", \"materialDescription\": \"Material\", \"quantity\": 1000,"
                + " \"uom\": \"" + uom + "\"" + physical + "}";
    }

    /** source|version|length|height|mode|nest|stackMax|orientation|unitWeight, '-' for null. */
    private static String lineRow(String reference) {
        return value("SELECT concat_ws('|', coalesce(l.physical_source, '-'),"
                + " coalesce(l.material_profile_version::text, '-'), coalesce(l.unit_length_m::text, '-'),"
                + " coalesce(l.unit_height_m::text, '-'), coalesce(l.stack_mode, '-'),"
                + " coalesce(l.nest_increment_m::text, '-'), coalesce(l.stack_max_quantity::text, '-'),"
                + " coalesce(l.orientation_locked::text, '-'), coalesce(l.unit_weight_kg::text, '-'))"
                + " FROM tms.transport_order_line l JOIN tms.transport_order o ON o.id = l.order_id"
                + " WHERE o.external_reference = '" + reference + "'");
    }

    private static String location(String code, String role) {
        String id = value("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY + "', '" + code
                + "', '" + code + "') RETURNING id");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
        return id;
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
