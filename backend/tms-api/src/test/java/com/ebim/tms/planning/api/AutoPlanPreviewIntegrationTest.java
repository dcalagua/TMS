package com.ebim.tms.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * {@code GET /planning/runs/{id}/auto-plan/preview} against real PostgreSQL, with the routing cache
 * cold - the case RC-1 was.
 *
 * <p><b>What failed.</b> {@code AutoPlanningService.preview} is {@code @Transactional(readOnly =
 * true)}. It asks {@code RoutingPort.matrix} for every leg between the run's located places, and
 * routing's {@code REQUIRED} transaction joins the read-only one, whose connection Spring and pgjdbc
 * open as {@code BEGIN READ ONLY}. The first leg nobody had cached was inserted into the cache from
 * inside it; PostgreSQL answered {@code 25006 cannot execute INSERT in a read-only transaction},
 * the transaction was aborted, and the preview died with it. A mocked cache cannot show this - only
 * a real {@code READ ONLY} transaction refuses the insert - and no test before this one called
 * {@code preview} against a database at all.
 *
 * <p><b>Why it looked intermittent.</b> Any read-write path that had measured the same legs first
 * - an applied plan, a trip edit - had warmed the cache, and a warm cache never inserts. So the
 * trigger is exactly what {@link #coldCachePreviewSucceedsAndWritesNothing} sets up: at least two
 * places with coordinates, eligible orders to them, and no cached leg between them.
 *
 * <p>Everything goes through the real HTTP filter chain, so the connection is also the tenant-scoped
 * {@code tms_app} one the drawer's request gets (ADR-005), not the owner's.
 *
 * <p>Each test owns a company, so the cache one test warms can never make another's cache look cold
 * or warm by accident, whatever order JUnit picks.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AutoPlanPreviewIntegrationTest.JwtDecoderOverride.class)
class AutoPlanPreviewIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";

    private static final UUID ORGANIZATION = UUID.fromString("7a7a7a7a-0000-4000-8000-000000000001");
    private static final UUID COMPANY_COLD = UUID.fromString("7a7a7a7a-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_EXPIRED = UUID.fromString("7a7a7a7a-0000-4000-8000-0000000000c2");
    private static final UUID PLANNER_AUTH = UUID.fromString("7a7a7a7a-0000-4000-8000-0000000000e1");

    // Lima and two stops a few kilometres away: close enough that nothing about the day is odd.
    private static final String ORIGIN_LAT = "-12.046374";
    private static final String ORIGIN_LON = "-77.042793";
    private static final String STOP_1_LAT = "-12.052780";
    private static final String STOP_1_LON = "-77.132790";
    private static final String STOP_2_LAT = "-12.121100";
    private static final String STOP_2_LON = "-77.029900";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoutingPort routingPort;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_auto_plan_preview");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'APP-ORG', 'Auto-plan Preview Org');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'APP-COLD', 'Cold cache', 'America/Lima'),
                    ('%s', '%s', 'APP-EXP', 'Expired cache', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'app.planner@example.invalid', 'Preview Planner');
                """.formatted(ORGANIZATION, COMPANY_COLD, ORGANIZATION, COMPANY_EXPIRED, ORGANIZATION,
                PLANNER_AUTH));
        membership(COMPANY_COLD);
        membership(COMPANY_EXPIRED);
    }

    private static void membership(UUID companyId) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'app.planner@example.invalid';

                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id
                FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'app.planner@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, companyId, companyId));
    }

    @BeforeEach
    void mintToken() {
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
    }

    /** The reproduction: coordinates, an empty cache, a preview. Before the fix this was a 500. */
    @Test
    @DisplayName("a preview over legs nobody has cached succeeds, uses the distances, and writes no cache row")
    void coldCachePreviewSucceedsAndWritesNothing() throws Exception {
        Day day = geocodedDay(COMPANY_COLD, "COLD");
        assertThat(cachedLegs(COMPANY_COLD)).as("the cache starts cold").isZero();

        preview(day, COMPANY_COLD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.ordersConsidered").value(2))
                // True only when routing answered the whole matrix: AutoPlanningService throws a
                // partial one away and plans without distances, which would report false here.
                .andExpect(jsonPath("$.kpis.distanceEstimated").value(true));

        // "Writing nothing" includes the cache: a read-only request does not store the legs it
        // measured. The apply that follows a preview is what warms it.
        assertThat(cachedLegs(COMPANY_COLD)).isZero();

        // And a second look is not a different code path that happens to work: still cold, still fine.
        preview(day, COMPANY_COLD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.distanceEstimated").value(true));
        assertThat(cachedLegs(COMPANY_COLD)).isZero();
    }

    /**
     * The quieter variant. Every leg is cached but expired, so the old code tried to refresh the
     * loaded rows in place - which in a read-only session is either refused or silently dropped,
     * and in neither case what the code claimed. The preview must succeed, and the rows must be
     * exactly as they were: not refreshed, not duplicated.
     */
    @Test
    @DisplayName("a preview over expired cached legs succeeds and leaves those rows untouched")
    void expiredCachePreviewSucceedsAndRefreshesNothing() throws Exception {
        Day day = geocodedDay(COMPANY_EXPIRED, "EXP");
        // Warmed through the port outside any caller's transaction - routing's own read-write one -
        // then aged honestly, both columns together, as RoutingServiceIntegrationTest does.
        List<GeoPoint> points = List.of(point(ORIGIN_LAT, ORIGIN_LON), point(STOP_1_LAT, STOP_1_LON),
                point(STOP_2_LAT, STOP_2_LON));
        assertThat(routingPort.matrix(COMPANY_EXPIRED, points, points)).hasSize(6);
        execute("UPDATE tms.travel_estimate"
                + " SET calculated_at = now() - interval '40 days', expires_at = now() - interval '1 day'"
                + " WHERE company_id = '" + COMPANY_EXPIRED + "'");
        assertThat(cachedLegs(COMPANY_EXPIRED)).isEqualTo(6);
        assertThat(expiredLegs(COMPANY_EXPIRED)).isEqualTo(6);

        preview(day, COMPANY_EXPIRED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersConsidered").value(2))
                .andExpect(jsonPath("$.kpis.distanceEstimated").value(true));

        assertThat(cachedLegs(COMPANY_EXPIRED)).isEqualTo(6);
        assertThat(expiredLegs(COMPANY_EXPIRED)).isEqualTo(6);
    }

    // --- fixture ----------------------------------------------------------------------

    private record Day(String runId, LocalDate date) {
    }

    /** A geocoded origin, two geocoded destinations, one order to each, and a draft run over them. */
    private Day geocodedDay(UUID companyId, String prefix) throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 1).plusDays(SEQUENCE.incrementAndGet());
        String origin = location(companyId, prefix + "-ORIGIN", "ORIGIN", ORIGIN_LAT, ORIGIN_LON);
        String stop1 = location(companyId, prefix + "-STOP-1", "DESTINATION", STOP_1_LAT, STOP_1_LON);
        String stop2 = location(companyId, prefix + "-STOP-2", "DESTINATION", STOP_2_LAT, STOP_2_LON);
        order(companyId, prefix, origin, stop1, date);
        order(companyId, prefix, origin, stop2, date);

        String response = mockMvc.perform(asPlanner(post(PLANNING + "/runs"), companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Day(JsonPath.read(response, "$.run.id"), date);
    }

    private org.springframework.test.web.servlet.ResultActions preview(Day day, UUID companyId) throws Exception {
        // PLANNING_V2 because it is the engine that reads distances; routing runs for either engine,
        // but only this one reports on the proposal whether it had them.
        return mockMvc.perform(asPlanner(get(PLANNING + "/runs/" + day.runId() + "/auto-plan/preview"), companyId)
                .param("engine", "PLANNING_V2"));
    }

    private MockHttpServletRequestBuilder asPlanner(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + plannerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static GeoPoint point(String latitude, String longitude) {
        return new GeoPoint(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    private static String location(UUID companyId, String code, String role, String latitude, String longitude) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name, latitude, longitude)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + code + " name', " + latitude + ", "
                + longitude + ")");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
        return id;
    }

    private static void order(UUID companyId, String prefix, String originId, String destinationId,
            LocalDate serviceDate) {
        insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, status, total_weight_kg, total_volume_m3, total_pallets) VALUES ('" + companyId
                + "', 'TO-" + prefix + "-" + String.format(java.util.Locale.ROOT, "%06d", SEQUENCE.incrementAndGet())
                + "', '" + originId + "', '" + destinationId + "', '" + serviceDate
                + "', 'READY_FOR_PLANNING', 100, 1, 1)");
    }

    private static long cachedLegs(UUID companyId) {
        return queryLong("SELECT count(*) FROM tms.travel_estimate WHERE company_id = '" + companyId + "'");
    }

    private static long expiredLegs(UUID companyId) {
        return queryLong("SELECT count(*) FROM tms.travel_estimate WHERE company_id = '" + companyId
                + "' AND expires_at < now()");
    }

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the auto-plan preview fixture", failed);
        }
    }

    private static long queryLong(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the auto-plan preview fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the auto-plan preview fixture", failed);
        }
    }
}
