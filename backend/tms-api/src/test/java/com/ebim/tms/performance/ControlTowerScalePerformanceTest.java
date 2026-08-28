package com.ebim.tms.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.planning.application.ControlTowerFilter;
import com.ebim.tms.planning.application.ControlTowerService;
import com.ebim.tms.planning.application.ControlTowerView;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The control tower does not get slower in proportion to the day (JOB 25).
 *
 * <h2>What this test asserts, and what it deliberately does not</h2>
 *
 * It asserts a <b>query count</b>. It does not assert a duration.
 *
 * <p>A duration measured here - laptop, Docker, a container ninety seconds old - varies by a factor
 * of three between runs on the same machine and says nothing whatever about a server. A test that
 * asserted on it would fail on a busy afternoon and pass on a quiet one, and everybody would learn
 * to re-run it rather than read it.
 *
 * <p>A query count is deterministic, and it is <b>the thing that actually breaks at volume</b>. An
 * N+1 is invisible at ten rows and fatal at ten thousand: it does not degrade gradually, it degrades
 * in proportion to the data, which is exactly the failure a 10,000-orders/day target implies. This
 * catches it on a fixture of sixty.
 *
 * <h2>The shape of the assertion</h2>
 *
 * The overview is run against a small day and a large one, and <b>the statement count must not
 * grow</b>. Not "must be under twenty" - an absolute number would need editing every time a panel is
 * added, and would be edited without thought. What matters is that tripling the shipments does not
 * triple the queries.
 *
 * <p>This is the screen a supervisor keeps open all day and reloads constantly, so it is the one
 * where an N+1 costs the most.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@ActiveProfiles("test")
class ControlTowerScalePerformanceTest {

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("77777777-0000-4000-8000-0000000000c1");

    /** A quiet day. */
    private static final LocalDate SMALL_DAY = LocalDate.of(2026, 10, 1);
    /** The same day with twelve times the shipments. */
    private static final LocalDate LARGE_DAY = LocalDate.of(2026, 10, 2);

    private static final int SMALL_TRIPS = 5;
    private static final int LARGE_TRIPS = 60;
    private static final int STOPS_PER_TRIP = 8;

    private static String jdbcUrl;

    @Autowired
    private ControlTowerService controlTowerService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QueryCounter queries;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_control_tower_perf");
        seed();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    @BeforeEach
    void setUp() {
        queries = new QueryCounter(entityManagerFactory);
    }

    private static CompanyScope scope() {
        return new CompanyScope(COMPANY, "PERF", "Perf Co", "America/Lima", UUID.randomUUID(),
                "ORG", "Org", EnumSet.allOf(Permission.class));
    }

    @Test
    @DisplayName("twelve times the shipments does not mean twelve times the queries")
    void theOverviewDoesNotScaleWithTheDay() {
        // Warm the context: the first call of a run pays for metadata and caches that have nothing
        // to do with how many shipments there are, and counting it would compare two different
        // things.
        controlTowerService.overview(scope(), new ControlTowerFilter(SMALL_DAY, null, null, null));
        controlTowerService.overview(scope(), new ControlTowerFilter(LARGE_DAY, null, null, null));

        long small = queries.count(() ->
                controlTowerService.overview(scope(), new ControlTowerFilter(SMALL_DAY, null, null, null)));
        long large = queries.count(() ->
                controlTowerService.overview(scope(), new ControlTowerFilter(LARGE_DAY, null, null, null)));

        // Equal is what a correctly batched read path gives. A small allowance is left for the
        // panels that legitimately do one extra lookup when they have any rows at all to look up -
        // an empty panel skips its destination and vehicle reads entirely, which is a real and
        // deliberate difference between a quiet day and a busy one, and is NOT proportional to
        // either.
        System.out.printf("[baseline] control tower overview: %d queries for %d shipments,"
                + " %d for %d%n", small, SMALL_TRIPS, large, LARGE_TRIPS);

        assertThat(large)
                .as("the control tower issued %d queries for %d shipments and %d for %d."
                        + " Growing with the day is an N+1, and it is invisible until a real"
                        + " company opens the screen", large, LARGE_TRIPS, small, SMALL_TRIPS)
                .isLessThanOrEqualTo(small + 4);
    }

    @Test
    @DisplayName("every panel is capped, so a bad day cannot return a bad day's worth of rows")
    void panelsAreCapped() {
        ControlTowerView view = controlTowerService.overview(
                scope(), new ControlTowerFilter(LARGE_DAY, null, null, null));

        // Sixty shipments and 480 stops behind it. A panel answers "is anything wrong", and the
        // honest shape of that is the worst few plus a total - never the whole set, which is what
        // the paginated board is for.
        assertThat(view.outstandingStops()).hasSizeLessThanOrEqualTo(20);
        assertThat(view.blockers()).hasSizeLessThanOrEqualTo(20);
        assertThat(view.advisories()).hasSizeLessThanOrEqualTo(40);
        assertThat(view.openExceptions()).hasSizeLessThanOrEqualTo(20);
        assertThat(view.workload()).hasSizeLessThanOrEqualTo(5);

        // ...and the totals behind the caps are real, so a capped panel is never a silent truncation.
        assertThat(view.summary().outstandingStops()).isGreaterThan(view.outstandingStops().size());
    }

    // --- fixture -----------------------------------------------------------------------------

    private static void seed() {
        try {
            seedConnection = PostgresTestDatabase.connect(jdbcUrl);
            seedRows();
        } catch (SQLException failed) {
            throw new IllegalStateException("could not open the performance fixture connection", failed);
        } finally {
            try {
                if (seedConnection != null) {
                    seedConnection.close();
                }
            } catch (SQLException ignored) {
                // The fixture is seeded; a connection that will not close cannot un-seed it.
            }
        }
    }

    private static void seedRows() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'PERF-ORG', 'Perf Org')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY + "', '" + ORGANIZATION + "', 'PERF', 'Perf Co', 'America/Lima')");

        // A real person for the actor columns. V25 pairs every lifecycle timestamp with whoever
        // caused it (ck_trip_*_actor_pair), so a departure with nobody who dispatched it is refused
        // - and rightly: JOB 07's refusal to invent a system actor is the same rule.
        String actor = idOf("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('"
                + UUID.randomUUID() + "', 'perf.dispatcher@example.invalid', 'Perf Dispatcher')"
                + " RETURNING id");

        String type = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'PVT', 'Rigid', 20000, 60, 30)"
                + " RETURNING id");
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', 'PERF-ORIG', 'Depot') RETURNING id");

        day(SMALL_DAY, origin, type, SMALL_TRIPS, 0, actor);
        day(LARGE_DAY, origin, type, LARGE_TRIPS, SMALL_TRIPS, actor);
    }

    /**
     * One operating day of shipments, each with a vehicle and {@code STOPS_PER_TRIP} stops.
     *
     * <p>Seeded through SQL rather than the API: this is about the read path, and driving sixty
     * shipments through the write path would measure that instead and take a minute doing it.
     */
    private static void day(LocalDate date, String origin, String vehicleType, int trips, int offset,
            String actor) {
        String run = idOf("INSERT INTO tms.planning_run (company_id, plan_number, origin_id,"
                + " planning_date, status) VALUES ('" + COMPANY + "', 'PL-" + date + "', '" + origin
                + "', '" + date + "', 'DRAFT') RETURNING id");

        for (int i = 0; i < trips; i++) {
            int number = offset + i + 1;
            String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                    + " license_plate) VALUES ('" + COMPANY + "', '" + vehicleType + "', 'PV-" + number
                    + "', 'PLT-" + number + "') RETURNING id");
            // confirmed_at and actual_departure_at are not decoration: V25 refuses an IN_TRANSIT
            // shipment that was never confirmed and never left, and it is right to. The first
            // version of this fixture omitted them and the constraint caught it.
            String trip = idOf("INSERT INTO tms.trip (company_id, planning_run_id, planning_date,"
                    + " trip_number, vehicle_id, status, planned_departure_at, confirmed_at,"
                    + " actual_departure_at, snapshot_max_weight_kg, snapshot_max_volume_m3,"
                    + " snapshot_max_pallets, capacity_snapshot_at, confirmed_by, dispatched_by,"
                    + " ready_at, ready_by)"
                    + " VALUES ('" + COMPANY + "', '"
                    + run + "', '" + date + "', " + number + ", '" + vehicle + "', 'IN_TRANSIT',"
                    + " '" + date + "T08:00:00Z', '" + date + "T07:00:00Z', '" + date + "T08:05:00Z',"
                    + " 20000, 60, 30, '" + date + "T07:00:00Z', '" + actor + "', '" + actor + "',"
                    + " '" + date + "T07:30:00Z', '" + actor + "') RETURNING id");

            for (int stop = 1; stop <= STOPS_PER_TRIP; stop++) {
                // tms.location, not tms.destination: a later migration repointed
                // fk_trip_stop_destination at the canonical location model, and the FK said so.
                String destination = idOf("INSERT INTO tms.location (company_id, code, name)"
                        + " VALUES ('" + COMPANY + "', 'PD-" + number + "-" + stop + "',"
                        + " 'Customer " + number + "-" + stop + "') RETURNING id");
                execute("INSERT INTO tms.trip_stop (company_id, trip_id, destination_id, sequence,"
                        + " execution_status) VALUES ('" + COMPANY + "', '" + trip + "', '" + destination
                        + "', " + stop + ", 'PENDING')");
            }
        }
    }

    /**
     * One connection for the whole fixture.
     *
     * <p>Held open rather than reconnecting per statement: this fixture issues around a thousand
     * inserts, and a fresh TCP connection and authentication apiece turned a two-second seed into a
     * minute. The other integration tests reconnect because they seed a dozen rows and clarity wins;
     * here it does not.
     */
    private static Connection seedConnection;

    private static String idOf(String sql) {
        try (Statement statement = seedConnection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the performance fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Statement statement = seedConnection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the performance fixture", failed);
        }
    }
}
