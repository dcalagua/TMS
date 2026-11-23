package com.ebim.tms.masterdata.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.reference.StopSequenceAdvisorPort;
import com.ebim.tms.shared.security.TestJwts;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * "Optimizar paradas" (ADR-030) through the real filter chain, a migrated PostgreSQL and the real
 * {@code RoutingPort} - with the AI replaced by {@link FakeAdvisor}. No test here talks to
 * Anthropic: a test suite that spends money and fails when a vendor is down is not a test suite.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RouteStopOptimizationApiIntegrationTest.Overrides.class)
class RouteStopOptimizationApiIntegrationTest {

    private static final String OPTIMIZE = "/api/v1/masterdata/routes/optimize-stops";

    private static final UUID ORGANIZATION = UUID.fromString("47777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("47777777-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("47777777-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("47777777-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("47777777-0000-4000-8000-0000000000e2");

    private static String jdbcUrl;
    private static String origin;
    private static String far;
    private static String near;
    private static String withoutCoordinates;
    private static String otherCompanyStop;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String viewerToken;

    /** The AI, as a test controls it: whether it is configured, and what it answers. */
    static final class FakeAdvisor implements StopSequenceAdvisorPort {
        static volatile boolean available = true;
        static volatile Function<StopSequenceQuestion, List<Integer>> answer = question -> List.of();
        static final AtomicReference<StopSequenceQuestion> lastQuestion = new AtomicReference<>();

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public StopSequenceAdvice advise(StopSequenceQuestion question) {
            lastQuestion.set(question);
            return new StopSequenceAdvice(answer.apply(question), "Primero la parada 2, que está más cerca.", "fake-model");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }

        @Bean
        @Primary
        StopSequenceAdvisorPort fakeAdvisor() {
            return new FakeAdvisor();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_route_optimize_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'RO-ORG', 'Route Optimize Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'RO-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'RO-B', 'Company B', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'ro.admin@example.invalid', 'RO Admin'),
                    ('%s', 'ro.viewer@example.invalid', 'RO Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));
        membership("ro.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("ro.viewer@example.invalid", COMPANY_A, "VIEWER");

        // On one meridian, so the geometry is obvious: the near stop is 0.1 degree south of the
        // origin and the far one 0.2. Visiting far first doubles back; near first does not.
        origin = insertLocation(COMPANY_A, "RO-CD", "ORIGIN", "-12.000000", "-77.000000");
        far = insertLocation(COMPANY_A, "RO-FAR", "DESTINATION", "-12.200000", "-77.000000");
        near = insertLocation(COMPANY_A, "RO-NEAR", "DESTINATION", "-12.100000", "-77.000000");
        withoutCoordinates = insertLocation(COMPANY_A, "RO-NOGEO", "DESTINATION", null, null);
        otherCompanyStop = insertLocation(COMPANY_B, "RO-B1", "DESTINATION", "-12.050000", "-77.000000");
    }

    @BeforeEach
    void reset() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
        FakeAdvisor.available = true;
        FakeAdvisor.answer = question -> List.of();
        FakeAdvisor.lastQuestion.set(null);
    }

    @Test
    @DisplayName("the proposal comes back in destination ids, measured against the current order, and only geometry was asked")
    void proposalIsMeasuredAgainstTheCurrentOrder() throws Exception {
        FakeAdvisor.answer = question -> List.of(2, 1);

        String body = optimize(asAdmin(post(OPTIMIZE)), origin, far, near)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationIds[0]").value(near))
                .andExpect(jsonPath("$.destinationIds[1]").value(far))
                .andExpect(jsonPath("$.improved").value(true))
                .andExpect(jsonPath("$.estimated").value(true))
                .andExpect(jsonPath("$.model").value("fake-model"))
                .andExpect(jsonPath("$.rationale").value("Primero la parada 2, que está más cerca."))
                .andReturn().getResponse().getContentAsString();
        double current = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.currentDistanceKm")).doubleValue();
        double proposed = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.proposedDistanceKm")).doubleValue();
        // Far first drives 0.2 + 0.1 degrees of latitude; near first 0.1 + 0.1.
        assertThat(proposed).isPositive().isLessThan(current);

        StopSequenceAdvisorPort.StopSequenceQuestion asked = FakeAdvisor.lastQuestion.get();
        assertThat(asked.stops()).hasSize(2);
        assertThat(asked.distanceKm()).hasSize(3).allSatisfy(row -> assertThat(row).hasSize(3));
        // The order the planner sent: far is stop 1, near is stop 2.
        assertThat(asked.stops().get(0).latitude()).isEqualByComparingTo("-12.2");
    }

    @Test
    @DisplayName("keeping the current order is a valid answer, reported as not improved")
    void currentOrderIsNotAnImprovement() throws Exception {
        FakeAdvisor.answer = question -> List.of(1, 2);

        optimize(asAdmin(post(OPTIMIZE)), origin, near, far)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationIds[0]").value(near))
                .andExpect(jsonPath("$.improved").value(false));
    }

    @Test
    @DisplayName("an answer that drops or repeats a stop is refused as ai-unavailable, never shown")
    void invalidSequenceIsRefused() throws Exception {
        FakeAdvisor.answer = question -> List.of(1, 1);

        optimize(asAdmin(post(OPTIMIZE)), origin, far, near)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ai-unavailable"));
    }

    @Test
    @DisplayName("a stop without coordinates is missing-coordinates, and the AI is not asked")
    void missingCoordinates() throws Exception {
        optimize(asAdmin(post(OPTIMIZE)), origin, far, withoutCoordinates)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing-coordinates"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RO-NOGEO")));

        assertThat(FakeAdvisor.lastQuestion.get()).isNull();
    }

    @Test
    @DisplayName("with no API key the endpoint answers 503 feature-not-configured")
    void notConfigured() throws Exception {
        FakeAdvisor.available = false;

        optimize(asAdmin(post(OPTIMIZE)), origin, far, near)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("feature-not-configured"));
    }

    @Test
    @DisplayName("another company's location is not a stop of this company")
    void otherCompanyStopIsRefused() throws Exception {
        optimize(asAdmin(post(OPTIMIZE)), origin, far, otherCompanyStop)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("a read-only role may not ask: each call is an AI request and exists to edit a route")
    void readOnlyRoleCannotOptimize() throws Exception {
        optimize(asViewer(post(OPTIMIZE)), origin, far, near)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access-denied"));
    }

    @Test
    @DisplayName("one stop is not a sequence")
    void oneStopIsNotEnough() throws Exception {
        optimize(asAdmin(post(OPTIMIZE)), origin, far)
                .andExpect(status().isBadRequest());
    }

    private ResultActions optimize(MockHttpServletRequestBuilder request, String originId, String... stops)
            throws Exception {
        String ids = String.join("\",\"", stops);
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                .content("{\"originId\":\"" + originId + "\",\"destinationIds\":[\"" + ids + "\"]}"));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken).header(ApiHeaders.COMPANY_ID, COMPANY_A.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + viewerToken).header(ApiHeaders.COMPANY_ID, COMPANY_A.toString());
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = '%s';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%s'
                JOIN tms.role r ON r.code = '%s'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, companyId, email, email, roleCode, companyId));
    }

    private static String insertLocation(UUID companyId, String code, String role, String latitude, String longitude) {
        String coordinates = latitude == null ? "" : ", latitude, longitude";
        String values = latitude == null ? "" : ", " + latitude + ", " + longitude;
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name" + coordinates + ") VALUES ('"
                + companyId + "', '" + code + "', '" + code + "'" + values + ")");
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
            throw new IllegalStateException("could not seed a route optimisation fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed a route optimisation fixture", failed);
        }
    }
}
