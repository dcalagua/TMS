package com.ebim.tms.routing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.routing.infrastructure.TravelEstimateRepository;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.TravelEstimate;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The routing chain against real PostgreSQL (migration V38).
 *
 * <p>{@code RoutingServiceTest} proves which link answers, with the cache mocked. This proves the
 * part a mock cannot: that a computed leg actually survives a round trip through the generated grid
 * columns and comes back as a hit, and that one company's cached legs are invisible to another.
 *
 * <p><b>No network.</b> The only adapter in the context is
 * {@link LocalGeodesicRoutingProvider}, which needs nothing but two coordinates. There is no vendor
 * code in the tree to reach accidentally.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@ActiveProfiles("test")
class RoutingServiceIntegrationTest {

    private static final GeoPoint LIMA = point("-12.046374", "-77.042793");
    private static final GeoPoint CALLAO = point("-12.052780", "-77.132790");
    private static final GeoPoint AREQUIPA = point("-16.409047", "-71.537451");

    private static String jdbcUrl;
    private static UUID companyA;
    private static UUID companyB;

    @Autowired
    private RoutingService routingService;

    @Autowired
    private TravelEstimateRepository repository;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_routing_service");
        seedCompanies();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedCompanies() {
        execute("INSERT INTO tms.organization (code, name) VALUES ('RT-ORG', 'Routing Org')");
        execute("""
                INSERT INTO tms.company (organization_id, code, name, time_zone)
                SELECT o.id, v.code, v.name, 'America/Lima'
                FROM tms.organization o
                JOIN (VALUES ('RT-A', 'Routing A'), ('RT-B', 'Routing B')) AS v(code, name) ON true
                WHERE o.code = 'RT-ORG'
                """);
        companyA = companyId("RT-A");
        companyB = companyId("RT-B");
    }

    private static GeoPoint point(String latitude, String longitude) {
        return new GeoPoint(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    @Test
    @DisplayName("a leg is computed once, stored, and served from the cache the second time")
    void cacheRoundTrip() {
        TravelEstimate first = routingService.estimate(companyA, LIMA, AREQUIPA).orElseThrow();
        assertThat(first.source()).isEqualTo(RoutingSource.FALLBACK);
        assertThat(first.distanceKm()).isGreaterThan(BigDecimal.ZERO);

        TravelEstimate second = routingService.estimate(companyA, LIMA, AREQUIPA).orElseThrow();

        assertThat(second.servedFromCache()).isTrue();
        // Still an estimate. Storing it did not turn a straight line into a measured road.
        assertThat(second.source()).isEqualTo(RoutingSource.FALLBACK);
        // The same number, not a recomputation that happened to agree: the cached row carries the
        // moment the figure was produced, which a fresh computation would have moved.
        assertThat(second.distanceKm()).isEqualByComparingTo(first.distanceKm());
        assertThat(second.travelDuration()).isEqualTo(first.travelDuration());
        assertThat(second.calculatedAt()).isEqualTo(first.calculatedAt());
    }

    /**
     * The defect the generated grid exists to prevent. If the repository's rounding and the
     * database's generation ever disagreed, this would come back {@code FALLBACK} - a cache that
     * looks like it works and never hits.
     */
    @Test
    @DisplayName("a coordinate differing in the sixth decimal hits the same cached row")
    void neighbouringCoordinatesHitTheSameRow() {
        routingService.estimate(companyA, CALLAO, AREQUIPA);

        TravelEstimate nearby = routingService
                .estimate(companyA, point("-12.052799", "-77.132751"), AREQUIPA).orElseThrow();

        assertThat(nearby.servedFromCache()).isTrue();
    }

    /**
     * The tenancy line, exercised through the service rather than asserted about the schema:
     * company B computing the same road must not be served company A's row.
     */
    @Test
    @DisplayName("one company's cached legs are invisible to another")
    void theCacheDoesNotLeakAcrossCompanies() {
        routingService.estimate(companyA, LIMA, CALLAO).orElseThrow();

        TravelEstimate forB = routingService.estimate(companyB, LIMA, CALLAO).orElseThrow();

        // B computed it for itself. A cache hit here would mean B had read A's row.
        assertThat(forB.servedFromCache()).isFalse();
        assertThat(countLegs(companyA, LIMA, CALLAO)).isEqualTo(1);
        assertThat(countLegs(companyB, LIMA, CALLAO)).isEqualTo(1);
    }

    @Test
    @DisplayName("a matrix answers every pair and leaves one row per distinct leg")
    void matrixPersistsEachLegOnce() {
        List<GeoPoint> points = List.of(LIMA, CALLAO, AREQUIPA);

        Map<RoutingPort.Leg, TravelEstimate> answers = routingService.matrix(companyB, points, points);

        assertThat(answers).hasSize(6);
        assertThat(answers.values()).allSatisfy(estimate ->
                assertThat(estimate.distanceKm()).isGreaterThan(BigDecimal.ZERO));

        // Asked again, every one of them is now a hit - which is what makes an engine scoring
        // fifteen candidate stop orders affordable.
        Map<RoutingPort.Leg, TravelEstimate> again = routingService.matrix(companyB, points, points);
        assertThat(again.values()).allSatisfy(estimate ->
                assertThat(estimate.servedFromCache()).isTrue());
    }

    @Test
    @DisplayName("an expired row is refreshed in place rather than duplicated")
    void expiryRefreshesInPlace() {
        routingService.estimate(companyA, AREQUIPA, CALLAO).orElseThrow();
        long before = countLegs(companyA, AREQUIPA, CALLAO);
        assertThat(before).isEqualTo(1);

        // Aged honestly: both columns move. ck_travel_estimate_expiry_after_calculation refuses a
        // row that was born already expired, and an old row is not that - it was calculated long
        // ago and its window has since closed.
        execute("UPDATE tms.travel_estimate"
                + " SET calculated_at = now() - interval '40 days', expires_at = now() - interval '1 day'"
                + " WHERE company_id = '" + companyA + "'"
                + " AND origin_key_lat = round(CAST(-16.409047 AS numeric), 4)");

        TravelEstimate refreshed = routingService.estimate(companyA, AREQUIPA, CALLAO).orElseThrow();

        assertThat(refreshed.servedFromCache()).isFalse();
        assertThat(countLegs(companyA, AREQUIPA, CALLAO)).isEqualTo(1);
    }

    @Test
    @DisplayName("the retention sweep removes expired rows and leaves fresh ones")
    void evictionTrimsOnlyExpiredRows() {
        routingService.estimate(companyB, AREQUIPA, LIMA).orElseThrow();
        routingService.estimate(companyB, LIMA, AREQUIPA).orElseThrow();
        execute("UPDATE tms.travel_estimate"
                + " SET calculated_at = now() - interval '40 days', expires_at = now() - interval '1 day'"
                + " WHERE company_id = '" + companyB + "'"
                + " AND origin_key_lat = round(CAST(-16.409047 AS numeric), 4)");

        long freshBefore = count("SELECT count(*) FROM tms.travel_estimate WHERE expires_at > now()");
        int evicted = routingService.evictExpired();

        assertThat(evicted).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM tms.travel_estimate WHERE expires_at < now()")).isZero();
        assertThat(count("SELECT count(*) FROM tms.travel_estimate WHERE expires_at > now()"))
                .isEqualTo(freshBefore);
    }

    @Test
    @DisplayName("a location with no coordinates is an unknown distance, and nothing is stored for it")
    void missingCoordinatesStoreNothing() {
        long before = repository.count();

        assertThat(routingService.estimate(companyA, null, LIMA)).isEmpty();
        assertThat(routingService.estimate(companyA, LIMA, null)).isEmpty();

        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("with no vendor configured, the local estimator is what answers")
    void theLocalEstimatorIsTheActiveProvider() {
        assertThat(routingService.activeProviderName()).isEqualTo(LocalGeodesicRoutingProvider.NAME);
    }

    // --- fixtures --------------------------------------------------------------------

    private static long countLegs(UUID companyId, GeoPoint origin, GeoPoint destination) {
        return count("SELECT count(*) FROM tms.travel_estimate WHERE company_id = '" + companyId + "'"
                + " AND origin_key_lat = round(CAST(" + origin.latitude() + " AS numeric), 4)"
                + " AND destination_key_lat = round(CAST(" + destination.latitude() + " AS numeric), 4)");
    }

    private static UUID companyId(String code) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT id FROM tms.company WHERE code = '" + code + "'")) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the routing fixture", failed);
        }
    }

    private static long count(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the routing fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the routing fixture", failed);
        }
    }
}
