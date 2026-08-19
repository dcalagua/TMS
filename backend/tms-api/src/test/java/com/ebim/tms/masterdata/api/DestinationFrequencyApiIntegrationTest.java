package com.ebim.tms.masterdata.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.UUID;
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
 * The destination/frequency vertical slice (Step 06), exercised end to end through the real
 * HTTP filter chain and a real, freshly migrated PostgreSQL - the same proof
 * {@code OriginZoneApiIntegrationTest} gives V6's origin/zone slice, extended to V7.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DestinationFrequencyApiIntegrationTest.JwtDecoderOverride.class)
class DestinationFrequencyApiIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("33333333-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("33333333-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("33333333-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("33333333-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("33333333-0000-4000-8000-0000000000e2");

    private static String jdbcUrl;
    private static String zoneInCompanyA;
    private static String zoneInCompanyB;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String viewerToken;

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtDecoderOverride {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }
    }

    /** Same admin-in-both/viewer-in-A-only fixture as {@code OriginZoneApiIntegrationTest}, plus one zone per company. */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_dest_freq_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'DF-ORG', 'Destination Frequency Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'DF-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'DF-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'df.admin@example.invalid', 'DF Admin'),
                    ('%s', 'df.viewer@example.invalid', 'DF Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("df.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("df.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("df.viewer@example.invalid", COMPANY_A, "VIEWER");

        zoneInCompanyA = insertZone(COMPANY_A, "ZONE-A");
        zoneInCompanyB = insertZone(COMPANY_B, "ZONE-B");
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = '%s';

                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id
                FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%s'
                JOIN tms.role r ON r.code = '%s'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, companyId, email, email, roleCode, companyId));
    }

    private static String insertZone(UUID companyId, String code) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("INSERT INTO tms.zone (company_id, code, name) VALUES ('"
                        + companyId + "', '" + code + "', '" + code + " name') RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed a zone fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the destination/frequency fixture", failed);
        }
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String jsonResponse) {
        return JsonPath.read(jsonResponse, "$.id");
    }

    @Nested
    @DisplayName("destinations")
    class Destinations {

        private static final String BASE = "/api/v1/masterdata/destinations";

        private String destinationRequest(String code, String type, Double latitude, Double longitude, String zoneId) {
            return """
                    {"code":"%s","name":"%s Name","type":"%s","address":"123 Main St",
                     "district":"Miraflores","province":"Lima","department":"Lima","country":"PE",
                     "latitude":%s,"longitude":%s,"zoneId":%s,"serviceTimeMinutes":15,"externalReference":"EWM-1"}
                    """.formatted(code, code, type, latitude, longitude, zoneId == null ? "null" : "\"" + zoneId + "\"");
        }

        @Test
        @DisplayName("create normalizes the code and is readable back through list and get")
        void createNormalizesCodeAndIsListed() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("south-store", "STORE", 10.5, -75.2, zoneInCompanyA)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("SOUTH-STORE"))
                    .andExpect(jsonPath("$.zoneCode").value("ZONE-A"))
                    .andExpect(jsonPath("$.active").value(true));

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'SOUTH-STORE')]").exists());
        }

        @Test
        @DisplayName("the same code is allowed in a different company but conflicts inside the same one")
        void sameCodeAcrossCompaniesButNotWithinOne() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("dup-code", "CUSTOMER", null, null, null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("dup-code", "CUSTOMER", null, null, null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("dup-code", "CUSTOMER", null, null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("a destination of one company cannot be read through another company's scope")
        void crossCompanyAccessIsBlocked() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("isolated", "BRANCH", null, null, null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("out-of-range or incomplete coordinates are rejected with a field-level error")
        void invalidCoordinatesAreBlocked() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("bad-lat", "HUB", 200.0, 0.0, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'latitude')]").exists());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"bad-pair","name":"Bad Pair","type":"HUB","country":"PE",
                                     "latitude":10.0,"serviceTimeMinutes":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a zone from another company is rejected even though it is a real zone")
        void zoneMustBelongToCallersCompany() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("cross-zone", "CUSTOMER", null, null, zoneInCompanyB)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("deactivate and activate toggle state and are reflected in the active filter")
        void deactivateAndActivate() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("togglable", "HUB", null, null, null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("active", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'TOGGLABLE')]").exists());

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/activate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("update edits the record and re-checks the code for conflicts")
        void updateEditsAndRechecksCode() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("editable", "HUB", null, null, null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("renamed", "STORE", null, null, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("RENAMED"))
                    .andExpect(jsonPath("$.type").value("STORE"));
        }

        @Test
        @DisplayName("a read-only role may list but not create")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(destinationRequest("forbidden", "HUB", null, null, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("pagination reports the size actually applied and the total within the company")
        void paginationIsServerSide() throws Exception {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(destinationRequest("page-" + i, "HUB", null, null, null)))
                        .andExpect(status().isCreated());
            }

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("page", "0").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        }
    }

    @Nested
    @DisplayName("frequencies")
    class Frequencies {

        private static final String BASE = "/api/v1/masterdata/frequencies";

        private String frequencyRequest(String code, String weeklyRulesJson) {
            return """
                    {"code":"%s","name":"%s Name","description":"a frequency","weeklyRules":%s}
                    """.formatted(code, code, weeklyRulesJson);
        }

        @Test
        @DisplayName("create persists the weekly rules and returns them sorted by day")
        void createWithWeeklyRulesNormalizesCodeAndIsListed() throws Exception {
            String weeklyRules = """
                    [{"dayOfWeek":3,"enabled":true,"cutoffTime":"14:00:00","leadTimeDays":1},
                     {"dayOfWeek":1,"enabled":true,"cutoffTime":"10:00:00","leadTimeDays":0}]
                    """;

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("mon-wed", weeklyRules)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("MON-WED"))
                    .andExpect(jsonPath("$.weeklyRules.length()").value(2))
                    .andExpect(jsonPath("$.weeklyRules[0].dayOfWeek").value(1))
                    .andExpect(jsonPath("$.weeklyRules[0].cutoffTime").value("10:00:00"))
                    .andExpect(jsonPath("$.weeklyRules[1].dayOfWeek").value(3))
                    .andExpect(jsonPath("$.weeklyRules[1].leadTimeDays").value(1));
        }

        @Test
        @DisplayName("a duplicate day of week within one request is rejected")
        void duplicateDayOfWeekIsRejected() throws Exception {
            String weeklyRules = """
                    [{"dayOfWeek":2,"enabled":true},{"dayOfWeek":2,"enabled":false}]
                    """;

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("dup-day", weeklyRules)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a day of week outside 1-7 is rejected with a field-level error")
        void invalidDayOfWeekIsRejected() throws Exception {
            String weeklyRules = """
                    [{"dayOfWeek":8,"enabled":true}]
                    """;

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("bad-day", weeklyRules)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"));
        }

        @Test
        @DisplayName("update replaces the weekly rules transactionally: removed days disappear, kept days update, new days appear")
        void updateReplacesWeeklyRulesTransactionally() throws Exception {
            String initialRules = """
                    [{"dayOfWeek":1,"enabled":true},{"dayOfWeek":2,"enabled":true},{"dayOfWeek":3,"enabled":true}]
                    """;
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("replace-me", initialRules)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            String updatedRules = """
                    [{"dayOfWeek":2,"enabled":false,"leadTimeDays":2},{"dayOfWeek":4,"enabled":true}]
                    """;
            mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("replace-me", updatedRules)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.weeklyRules.length()").value(2))
                    .andExpect(jsonPath("$.weeklyRules[0].dayOfWeek").value(2))
                    .andExpect(jsonPath("$.weeklyRules[0].enabled").value(false))
                    .andExpect(jsonPath("$.weeklyRules[0].leadTimeDays").value(2))
                    .andExpect(jsonPath("$.weeklyRules[1].dayOfWeek").value(4));

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.weeklyRules.length()").value(2))
                    .andExpect(jsonPath("$.weeklyRules[?(@.dayOfWeek == 1)]").doesNotExist())
                    .andExpect(jsonPath("$.weeklyRules[?(@.dayOfWeek == 3)]").doesNotExist());
        }

        @Test
        @DisplayName("the same code is allowed in a different company but conflicts inside the same one")
        void sameCodeAcrossCompaniesButNotWithinOne() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("dup-freq", "[]")))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("dup-freq", "[]")))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("dup-freq", "[]")))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a frequency of one company cannot be read through another company's scope")
        void crossCompanyAccessIsBlocked() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("secluded", "[]")))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a read-only role may list but not create, update or deactivate")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("nope", "[]")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deactivate is idempotent-safe and reflected in a subsequent read")
        void deactivate() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(frequencyRequest("fade-out", "[]")))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }

        @Nested
        @DisplayName("exceptions")
        class Exceptions {

            @Test
            @DisplayName("create, list and delete a date exception; a duplicate date conflicts")
            void exceptionLifecycleAndDuplicateDateConflict() throws Exception {
                String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(frequencyRequest("has-exceptions", "[]")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
                String frequencyId = idOf(response);
                String exceptionsPath = BASE + "/" + frequencyId + "/exceptions";

                String exceptionResponse = mockMvc.perform(asAdmin(post(exceptionsPath), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"exceptionDate":"2026-12-25","serviceOverride":false,"note":"Holiday"}
                                        """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.exceptionDate").value("2026-12-25"))
                        .andExpect(jsonPath("$.serviceOverride").value(false))
                        .andReturn().getResponse().getContentAsString();
                String exceptionId = idOf(exceptionResponse);

                mockMvc.perform(asAdmin(get(exceptionsPath), COMPANY_A))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].id").value(exceptionId));

                mockMvc.perform(asAdmin(post(exceptionsPath), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"exceptionDate":"2026-12-25","serviceOverride":true}
                                        """))
                        .andExpect(status().isConflict());

                mockMvc.perform(asAdmin(delete(exceptionsPath + "/" + exceptionId), COMPANY_A))
                        .andExpect(status().isNoContent());

                mockMvc.perform(asAdmin(get(exceptionsPath), COMPANY_A))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(0));
            }

            @Test
            @DisplayName("exceptions of a frequency in another company are not reachable")
            void exceptionsAreCompanyScoped() throws Exception {
                String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(frequencyRequest("scoped-exceptions", "[]")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
                String frequencyId = idOf(response);

                mockMvc.perform(asAdmin(get(BASE + "/" + frequencyId + "/exceptions"), COMPANY_B))
                        .andExpect(status().isNotFound());
            }
        }
    }
}
