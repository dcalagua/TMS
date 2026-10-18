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

/**
 * ADR-022 section 1 and {@code WAREHOUSE_EXECUTION_V1.md} 7.1: the warehouse writes material
 * profiles, TMS keeps a dated copy, and a person reads it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MaterialProfileApiIntegrationTest.JwtDecoderOverride.class)
class MaterialProfileApiIntegrationTest {

    private static final String PROFILES = "/integration/v1/material-profiles";
    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c1");
    private static final UUID OTHER_COMPANY = UUID.fromString("99999999-0000-4000-8000-0000000000c2");
    private static final UUID VIEWER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e1");

    private static String jdbcUrl;
    private static String warehouseToken;
    private static String otherWarehouseToken;
    private static String orderOnlyToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_material_profiles");
        seed();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seed() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'MAT-ORG', 'Material Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'MAT-A', 'Material Company', 'America/Lima'),
                    ('%s', '%s', 'MAT-B', 'Other Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('%s', 'mat@example.invalid', 'Mat');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'mat@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m JOIN tms.role r ON r.code = 'VIEWER'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, OTHER_COMPANY, ORGANIZATION, VIEWER_AUTH,
                ORGANIZATION, COMPANY, COMPANY));
        warehouseToken = client(COMPANY, "integration.material:write");
        otherWarehouseToken = client(OTHER_COMPANY, "integration.material:write");
        orderOnlyToken = client(COMPANY, "integration.order:write");
    }

    @Test
    @DisplayName("first PUT is 201 CREATED with version 0; the same content again is 200 UNCHANGED")
    void createThenUnchanged() throws Exception {
        String code = "PLP-571-" + UUID.randomUUID();
        put(warehouseToken, code, plp571("2026-10-18T14:05:12-05:00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.materialCode").value(code))
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.version").value(0));
        // 0.1050 is what the column stores for 0.105: the same number, not a change.
        put(warehouseToken, code, plp571("2026-10-18T14:05:12-05:00").replace("0.105,", "0.1050,"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.version").value(0));

        assertThat(value("SELECT stack_mode || ':' || nest_increment_m || ':' || stack_max_quantity"
                + " FROM tms.material_profile WHERE material_code = '" + code + "'")).isEqualTo("NESTABLE:0.0120:100");
        assertThat(value("SELECT count(*) FROM tms.audit_event WHERE action = 'MATERIAL_PROFILE_CREATED'"
                + " AND metadata LIKE '%" + code + "%'")).isEqualTo("1");
    }

    @Test
    @DisplayName("an absent field keeps the stored value, an explicit null clears it")
    void absentKeepsNullClears() throws Exception {
        String code = "PLP-1601-" + UUID.randomUUID();
        put(warehouseToken, code, plp571("2026-10-18T10:00:00Z"), null).andExpect(status().isCreated());

        put(warehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T11:00:00Z",
                 "heightM": 0.2, "unitWeightKg": null}
                """, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UPDATED"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(value("SELECT height_m || ':' || length_m || ':' || coalesce(unit_weight_kg::text, 'null')"
                + " || ':' || stack_mode FROM tms.material_profile WHERE material_code = '" + code + "'"))
                .isEqualTo("0.2000:0.1050:null:NESTABLE");
        assertThat(value("SELECT count(*) FROM tms.audit_event WHERE action = 'MATERIAL_PROFILE_UPDATED'"
                + " AND metadata LIKE '%" + code + "%'")).isEqualTo("1");
    }

    @Test
    @DisplayName("an older sourceUpdatedAt is 200 STALE, applies nothing and reports the stored copy")
    void staleWriteIsNotApplied() throws Exception {
        String code = "STALE-" + UUID.randomUUID();
        put(warehouseToken, code, plp571("2026-10-18T12:00:00Z"), null).andExpect(status().isCreated());

        put(warehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T11:59:59Z", "heightM": 9.9}
                """, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STALE"))
                .andExpect(jsonPath("$.version").value(0));

        assertThat(value("SELECT height_m FROM tms.material_profile WHERE material_code = '" + code + "'"))
                .isEqualTo("0.1750");
    }

    @Test
    @DisplayName("NESTABLE without nestIncrementM and a non-positive measure are 400, and nothing is stored")
    void shapeErrorsAre400() throws Exception {
        String code = "BAD-" + UUID.randomUUID();
        put(warehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T12:00:00Z", "stackMode": "NESTABLE",
                 "lengthM": 0.1, "widthM": 0.1, "heightM": 0.1}
                """, null).andExpect(status().isBadRequest());
        put(warehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T12:00:00Z", "lengthM": 0}
                """, null).andExpect(status().isBadRequest());
        put(warehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "lengthM": 0.1}
                """, null).andExpect(status().isBadRequest());
        put(warehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T12:00:00Z", "stackMode": "stackable"}
                """, null).andExpect(status().isBadRequest());

        assertThat(value("SELECT count(*) FROM tms.material_profile WHERE material_code = '" + code + "'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("the same Idempotency-Key replays the first answer; with another body it is 409")
    void idempotencyKey() throws Exception {
        String code = "IDEM-" + UUID.randomUUID();
        String key = "matp-" + UUID.randomUUID();
        put(warehouseToken, code, plp571("2026-10-18T12:00:00Z"), key)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("CREATED"));
        put(warehouseToken, code, plp571("2026-10-18T12:00:00Z"), key)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("CREATED"));
        put(warehouseToken, code, plp571("2026-10-18T12:30:00Z"), key)
                .andExpect(status().isConflict());

        assertThat(value("SELECT version FROM tms.material_profile WHERE material_code = '" + code + "'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("a credential without integration.material:write is 403")
    void scopeIsRequired() throws Exception {
        put(orderOnlyToken, "NOSCOPE-1", plp571("2026-10-18T12:00:00Z"), null).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("each company has its own copy: another tenant's write never touches this one, nor shows here")
    void tenantIsolation() throws Exception {
        String code = "SHARED-" + UUID.randomUUID();
        put(warehouseToken, code, plp571("2026-10-18T12:00:00Z"), null).andExpect(status().isCreated());
        put(otherWarehouseToken, code, """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T13:00:00Z", "heightM": 0.5}
                """, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"));

        assertThat(value("SELECT height_m FROM tms.material_profile WHERE company_id = '" + COMPANY
                + "' AND material_code = '" + code + "'")).isEqualTo("0.1750");
        String otherId = value("SELECT id FROM tms.material_profile WHERE company_id = '" + OTHER_COMPANY
                + "' AND material_code = '" + code + "'");

        mockMvc.perform(get("/api/v1/masterdata/material-profiles").param("search", code)
                        .header("Authorization", "Bearer " + TestJwts.validFor(VIEWER_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].heightM").value(0.175))
                .andExpect(jsonPath("$.content[0].sourceSystem").value("EWM_EBIM"));
        mockMvc.perform(get("/api/v1/masterdata/material-profiles/" + otherId)
                        .header("Authorization", "Bearer " + TestJwts.validFor(VIEWER_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a batch is not atomic: an INVALID item makes it 207 and the others are applied")
    void batchIsPerItem() throws Exception {
        String good = "BATCH-OK-" + UUID.randomUUID();
        String bad = "BATCH-BAD-" + UUID.randomUUID();
        mockMvc.perform(post(PROFILES + "/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceSystem": "EWM_EBIM", "profiles": [
                                  {"materialCode": "%s", "sourceUpdatedAt": "2026-10-18T14:05:12-05:00",
                                   "lengthM": 0.105, "widthM": 0.105, "heightM": 0.175, "stackMode": "NESTABLE",
                                   "stackMaxQuantity": 100, "nestIncrementM": 0.012},
                                  {"materialCode": "%s", "sourceUpdatedAt": "2026-10-18T14:05:12-05:00",
                                   "stackMode": "NESTABLE"}
                                ]}
                                """.formatted(good, bad)))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.refused").value(1))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[1].index").value(1))
                .andExpect(jsonPath("$.results[1].outcome").value("INVALID"))
                .andExpect(jsonPath("$.results[1].message").isNotEmpty());

        assertThat(value("SELECT count(*) FROM tms.material_profile WHERE material_code IN ('" + good + "', '"
                + bad + "')")).isEqualTo("1");
    }

    private ResultActions put(String token, String code, String body, String idempotencyKey) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(PROFILES + "/" + code)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private static String plp571(String sourceUpdatedAt) {
        return """
                {"sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "%s", "description": "VASO PLP-571 7 OZ",
                 "lengthM": 0.105, "widthM": 0.105, "heightM": 0.175, "unitWeightKg": 0.012,
                 "unitVolumeM3": 0.0019, "stackMode": "NESTABLE", "stackMaxQuantity": 100,
                 "nestIncrementM": 0.012, "maxTopLoadKg": null, "orientationLocked": false}
                """.formatted(sourceUpdatedAt);
    }

    private static String client(UUID company, String scope) {
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();
        String row = value("INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash) VALUES ('"
                + company + "', '" + clientId + "', 'EWM " + scope + "', '" + IntegrationSecrets.hash(secret) + "') RETURNING id");
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
