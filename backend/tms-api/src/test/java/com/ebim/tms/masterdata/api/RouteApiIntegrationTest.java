package com.ebim.tms.masterdata.api;

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
 * The route vertical slice (Step 07), exercised end to end through the real HTTP filter chain
 * and a real, freshly migrated PostgreSQL - the same proof
 * {@code DestinationFrequencyApiIntegrationTest} gives V7's slice, extended to V8's routes.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RouteApiIntegrationTest.JwtDecoderOverride.class)
class RouteApiIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/routes";

    private static final UUID ORGANIZATION = UUID.fromString("44444444-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("44444444-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("44444444-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("44444444-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("44444444-0000-4000-8000-0000000000e2");

    private static String jdbcUrl;
    private static String originInCompanyA;
    private static String originInCompanyB;
    private static String zoneInCompanyA;
    private static String frequencyInCompanyA;
    private static String destinationA1;
    private static String destinationA2;
    private static String destinationA3;
    private static String destinationInCompanyB;

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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_route_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'RT-ORG', 'Route Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'RT-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'RT-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'rt.admin@example.invalid', 'RT Admin'),
                    ('%s', 'rt.viewer@example.invalid', 'RT Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("rt.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("rt.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("rt.viewer@example.invalid", COMPANY_A, "VIEWER");

        originInCompanyA = insertReturningId(
                "INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_A + "', 'ORIGIN-A', 'Origin A')");
        originInCompanyB = insertReturningId(
                "INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_B + "', 'ORIGIN-B', 'Origin B')");
        zoneInCompanyA = insertReturningId(
                "INSERT INTO tms.zone (company_id, code, name) VALUES ('" + COMPANY_A + "', 'ZONE-A', 'Zone A')");
        frequencyInCompanyA = insertReturningId("INSERT INTO tms.frequency (company_id, code, name) VALUES ('"
                + COMPANY_A + "', 'FREQ-A', 'Frequency A')");
        destinationA1 = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_A + "', 'DEST-A1', 'Destination A1', 'PE')");
        destinationA2 = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_A + "', 'DEST-A2', 'Destination A2', 'PE')");
        destinationA3 = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_A + "', 'DEST-A3', 'Destination A3', 'PE')");
        destinationInCompanyB = insertReturningId(
                "INSERT INTO tms.destination (company_id, code, name, country) VALUES ('" + COMPANY_B
                        + "', 'DEST-B1', 'Destination B1', 'PE')");
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

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed a route fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the route fixture", failed);
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

    private String routeRequest(String code, String originId, String zoneId, String frequencyId, String... destinationIds) {
        String stops = String.join(",", java.util.Arrays.stream(destinationIds).map(id -> "\"" + id + "\"").toList());
        return """
                {"code":"%s","name":"%s Name","originId":"%s","zoneId":%s,"frequencyId":%s,
                 "referenceDistanceKm":12.5,"referenceDurationMinutes":45,"destinationIds":[%s]}
                """.formatted(code, code, originId, quoteOrNull(zoneId), quoteOrNull(frequencyId), stops);
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    @DisplayName("create persists the ordered stops and is readable back through get")
    void createWithOrderedStopsAndReadBack() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("north-corridor", originInCompanyA, zoneInCompanyA, frequencyInCompanyA,
                                destinationA1, destinationA2, destinationA3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("NORTH-CORRIDOR"))
                .andExpect(jsonPath("$.originCode").value("ORIGIN-A"))
                .andExpect(jsonPath("$.zoneCode").value("ZONE-A"))
                .andExpect(jsonPath("$.frequencyCode").value("FREQ-A"))
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[0].sequence").value(1))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A2"))
                .andExpect(jsonPath("$.stops[1].sequence").value(2))
                .andExpect(jsonPath("$.stops[2].destinationCode").value("DEST-A3"))
                .andExpect(jsonPath("$.stops[2].sequence").value(3))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].sequence").value(1));
    }

    @Test
    @DisplayName("list shows a stop count, never the destinations of each row")
    void listShowsStopCountNotFullStops() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("counted-route", originInCompanyA, null, null, destinationA1, destinationA2)))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("code", "counted-route"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].stopCount").value(2))
                .andExpect(jsonPath("$.content[0].stops").doesNotExist());
    }

    @Test
    @DisplayName("the same code is allowed in a different company but conflicts inside the same one")
    void sameCodeAcrossCompaniesButNotWithinOne() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("dup-code", originInCompanyA, null, null, destinationA1)))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("dup-code", originInCompanyB, null, null, destinationInCompanyB)))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("dup-code", originInCompanyA, null, null, destinationA1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @Test
    @DisplayName("a route of one company cannot be read through another company's scope")
    void crossCompanyAccessIsBlocked() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("isolated", originInCompanyA, null, null, destinationA1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource-not-found"));
    }

    @Test
    @DisplayName("an origin from another company is rejected even though it is a real origin")
    void originMustBelongToCallersCompany() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("cross-origin", originInCompanyB, null, null, destinationA1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("a destination from another company is rejected even though it is a real destination")
    void destinationMustBelongToCallersCompany() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("cross-destination", originInCompanyA, null, null, destinationInCompanyB)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("a repeated destination within the same request is rejected")
    void duplicateDestinationInRequestIsRejected() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("dup-dest", originInCompanyA, null, null, destinationA1, destinationA1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("a route with no destination stops is rejected")
    void emptyDestinationsAreRejected() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("no-stops", originInCompanyA, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    @Test
    @DisplayName("update replaces the stop order transactionally: a dropped stop disappears, kept stops re-sequence")
    void updateReordersStopsTransactionally() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("reorder-me", originInCompanyA, null, null,
                                destinationA1, destinationA2, destinationA3)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("reorder-me", originInCompanyA, null, null, destinationA3, destinationA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A3"))
                .andExpect(jsonPath("$.stops[0].sequence").value(1))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[1].sequence").value(2));

        mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[?(@.destinationCode == 'DEST-A2')]").doesNotExist());
    }

    @Test
    @DisplayName("deactivate and activate toggle state and are reflected in the active filter")
    void deactivateAndActivate() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("togglable", originInCompanyA, null, null, destinationA1)))
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
    @DisplayName("a read-only role may list but not create")
    void readOnlyRoleCannotManage() throws Exception {
        mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

        mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("forbidden", originInCompanyA, null, null, destinationA1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access-denied"));
    }

    @Test
    @DisplayName("pagination reports the size actually applied and the total within the company")
    void paginationIsServerSide() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(routeRequest("page-" + i, originInCompanyA, null, null, destinationA1)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("deactivating a destination used by a route does not remove the route's stop history")
    void deactivatingADestinationPreservesRouteHistory() throws Exception {
        String deactivatable = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country)"
                + " VALUES ('" + COMPANY_A + "', 'DEACTIVATABLE', 'Deactivatable', 'PE')");

        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeRequest("history-route", originInCompanyA, null, null, deactivatable)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(post("/api/v1/masterdata/destinations/" + deactivatable + "/deactivate"), COMPANY_A))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(1))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEACTIVATABLE"));
    }
}
