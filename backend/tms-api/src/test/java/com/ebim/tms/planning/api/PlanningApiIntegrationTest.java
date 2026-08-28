package com.ebim.tms.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * The manual-planning vertical slice (Step 10), exercised end to end through the real HTTP filter
 * chain and a real, freshly migrated PostgreSQL - the proof {@code OrderApiIntegrationTest} gives
 * V10's slice, extended to planning: eligible orders, runs, trips, capacity in all three
 * dimensions, assignment/removal/moves, concurrency, confirmation and its locking, and
 * permissions.
 *
 * <p>Every test opens its own planning run on its own planning date. That is not tidiness: the
 * partial unique index {@code uq_planning_run_open_scope} allows only one open draft per
 * company/origin/date, and MockMvc requests commit for real, so tests that shared a date would
 * collide on it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlanningApiIntegrationTest.JwtDecoderOverride.class)
class PlanningApiIntegrationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";

    private static final UUID ORGANIZATION = UUID.fromString("66666666-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("66666666-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("66666666-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e2");

    /** Every fixture row that must be unique gets its number from here, never from a literal. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String originA;
    private static String originA2;
    private static String destinationA1;
    private static String destinationA2;
    private static String carrierA;
    private static String originB;
    private static String destinationB;
    private static String routeA;
    private static String routeOtherOriginA;
    private static String routeB;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_planning_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'PLN-ORG', 'Planning Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'PLN-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'PLN-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'pln.admin@example.invalid', 'PLN Admin'),
                    ('%s', 'pln.viewer@example.invalid', 'PLN Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("pln.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("pln.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("pln.viewer@example.invalid", COMPANY_A, "VIEWER");

        originA = insertLocation(COMPANY_A, "ORIGIN-A", "Origin A", "ORIGIN");
        originA2 = insertLocation(COMPANY_A, "ORIGIN-A2", "Origin A2", "ORIGIN");
        // Coordinates on purpose: a shipment's stops must come back map-ready (Job 07,
        // "map-ready lat/lng data") and destinationA2 deliberately has none, so the
        // "degrade gracefully for a store nobody geocoded" case is exercised too.
        destinationA1 = insertLocation(COMPANY_A, "DEST-A1", "Destination A1", "DESTINATION",
                ", latitude, longitude", ", -12.046374, -77.042793");
        destinationA2 = insertLocation(COMPANY_A, "DEST-A2", "Destination A2", "DESTINATION");
        carrierA = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY_A + "', 'CARR-A', 'Carrier A', 'RUC', '20100000001')");
        originB = insertLocation(COMPANY_B, "ORIGIN-B", "Origin B", "ORIGIN");
        destinationB = insertLocation(COMPANY_B, "DEST-B", "Destination B", "DESTINATION");

        // Master routes, for the shipment/route relationship. routeA serves A2 before A1, which is
        // the reverse of the order assignments naturally produce - so "applying the route
        // reordered the stops" is visible rather than coincidental.
        routeA = route(COMPANY_A, "ROUTE-A", originA, List.of(destinationA2, destinationA1));
        routeOtherOriginA = route(COMPANY_A, "ROUTE-A-ALT", originA2, List.of(destinationA1));
        routeB = route(COMPANY_B, "ROUTE-B", originB, List.of(destinationB));
    }

    /** A master route with its ordered stops, inserted directly - masterdata's API is not under test here. */
    private static String route(UUID companyId, String code, String originId, List<String> destinationIds) {
        String routeId = insertReturningId("INSERT INTO tms.route (company_id, code, name, origin_id) VALUES ('"
                + companyId + "', '" + code + "', '" + code + " corridor', '" + originId + "')");
        int sequence = 1;
        for (String destinationId : destinationIds) {
            execute("INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence) VALUES ('"
                    + routeId + "', '" + companyId + "', '" + destinationId + "', " + sequence++ + ")");
        }
        return routeId;
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

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // --- eligible orders ----------------------------------------------------------

    @Test
    @DisplayName("eligible orders lists only orders ready for planning, and drops one as soon as it is assigned")
    void eligibleOrdersReflectAssignment() throws Exception {
        LocalDate date = nextDate();
        String ready = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        String notReady = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "NOT_READY");

        mockMvc.perform(asAdmin(get(PLANNING + "/eligible-orders"), COMPANY_A)
                        .param("originId", originA).param("serviceDate", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ready))
                .andExpect(jsonPath("$.content[0].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.content[0].lines").doesNotExist());

        Run run = newRun(date);
        String trip = newTrip(run, vehicle("BIG", "10000", "40", 20));
        assign(trip, ready).andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(PLANNING + "/eligible-orders"), COMPANY_A)
                        .param("originId", originA).param("serviceDate", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(orderStatus(notReady)).isEqualTo("NOT_READY");
        assertThat(orderStatus(ready)).isEqualTo("PLANNED");
    }

    // --- stop ETA (V43, ADR-011) --------------------------------------------------

    /**
     * The wiring the pure {@code StopScheduleEngineTest} cannot prove: that the legs, the service
     * times and the windows actually reach the engine, and that what it returns lands on the stops.
     *
     * <p>The fixture's {@code ORIGIN-A} deliberately has no coordinates, so this run is measured
     * from a geocoded origin created here. No routing vendor is configured - ADR-010 ships none -
     * so the leg falls back to a straight line and the estimate says so. Asserting the source and
     * not only the times is the point: a FALLBACK figure is useful and is not the same claim as a
     * measured road, and the API has to keep the two apart.
     */
    @Test
    @DisplayName("recomputing an ETA stamps every stop, with the provenance of what it was built on")
    void etaIsComputedAndStamped() throws Exception {
        LocalDate date = nextDate();
        String geocodedOrigin = insertLocation(COMPANY_A, "ORIGIN-ETA-" + date.toString().replace("-", ""),
                "Origin with coordinates", "ORIGIN", ", latitude, longitude", ", -12.020000, -77.100000");
        Run run = newRunFrom(geocodedOrigin, date);
        String trip = newTrip(run, vehicle("ETA", "10000", "40", 20));
        assign(trip, order(COMPANY_A, geocodedOrigin, destinationA1, date, "1000", "2", "2",
                "READY_FOR_PLANNING")).andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/eta"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].etaArrivalAt").exists())
                .andExpect(jsonPath("$.stops[0].etaDepartureAt").exists())
                .andExpect(jsonPath("$.stops[0].etaCalculatedAt").exists())
                // Straight-line, because no vendor adapter is configured. Named, not laundered.
                .andExpect(jsonPath("$.stops[0].etaSource").value("FALLBACK"));

        // And it is stamped, not derived on read: an ordinary GET returns the same numbers.
        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(jsonPath("$.stops[0].etaArrivalAt").exists())
                .andExpect(jsonPath("$.stops[0].etaSource").value("FALLBACK"));
    }

    /**
     * Rule 1 of {@code StopScheduleEngine}, end to end and not only in the unit test: the fixture's
     * {@code ORIGIN-A} has no coordinates, so the very first leg cannot be measured and <b>no stop
     * gets an estimate</b>.
     *
     * <p>The alternative - filling the gap with something plausible - is what makes a board show
     * arrival times of which several are wrong, with nothing saying which. A visible blank is the
     * honest answer, and this asserts the API actually returns one.
     */
    @Test
    @DisplayName("an origin with no coordinates leaves every stop without an ETA, rather than with a guess")
    void etaIsAbsentWhenTheFirstLegCannotBeMeasured() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ETA-NOGEO", "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "1000", "2", "2", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/eta"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].etaArrivalAt").doesNotExist())
                .andExpect(jsonPath("$.stops[0].etaSource").doesNotExist())
                // Not "misses its window" either: a stop with no estimate has not been shown to
                // miss anything, and a warning nobody can act on is worse than none.
                .andExpect(jsonPath("$.stops[0].etaMissesWindow").value(false));
    }

    /**
     * A schedule needs a starting instant. Falling back to {@code now()} would produce a board whose
     * arrival times changed every time somebody refreshed it, which is worse than not having one.
     */
    @Test
    @DisplayName("a shipment with no planned departure is refused, and told why")
    void etaNeedsADeparture() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, null);
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "1000", "2", "2", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/eta"), COMPANY_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("no planned departure")));
    }

    @Test
    @DisplayName("a trip with no stops schedules nothing and does not fail doing it")
    void etaOfAnEmptyTripIsEmpty() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ETA-EMPTY", "10000", "40", 20));

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/eta"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops").isEmpty());
    }

    @Test
    @DisplayName("a viewer cannot recompute an ETA")
    void etaRequiresAWriteAuthority() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ETA-AUTH", "10000", "40", 20));

        mockMvc.perform(asViewer(post(TRIPS + "/" + trip + "/eta"), COMPANY_A))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another company cannot recompute this company's ETA")
    void etaIsCompanyScoped() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ETA-TENANT", "10000", "40", 20));

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/eta"), COMPANY_B))
                .andExpect(status().isNotFound());
    }

    // --- capacity -----------------------------------------------------------------

    @Test
    @DisplayName("assigning an order reports server-computed utilisation in all three dimensions and creates its stop")
    void assignComputesCapacityAndCreatesStop() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("CAP", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "5000", "10", "15", "READY_FOR_PLANNING");

        assign(trip, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.capacity.source").value("LIVE"))
                .andExpect(jsonPath("$.trip.capacity.weight.used").value(5000.000))
                .andExpect(jsonPath("$.trip.capacity.weight.limit").value(10000.00))
                .andExpect(jsonPath("$.trip.capacity.weight.percentUsed").value(50.0))
                .andExpect(jsonPath("$.trip.capacity.volume.percentUsed").value(25.0))
                .andExpect(jsonPath("$.trip.capacity.pallets.percentUsed").value(75.0))
                .andExpect(jsonPath("$.trip.capacity.withinCapacity").value(true))
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].wholeOrder").value(true))
                .andExpect(jsonPath("$.stops.length()").value(1))
                .andExpect(jsonPath("$.stops[0].sequence").value(1))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1"));

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip + "/capacity"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight.remaining").value(5000.000))
                .andExpect(jsonPath("$.orderCount").value(1));
    }

    @Test
    @DisplayName("an over-weight order is rejected transactionally and leaves the trip empty")
    void overWeightIsRejected() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("WEIGHT", "1000", "50", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "1500", "1", "1", "READY_FOR_PLANNING");

        assign(trip, order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("weight 1500")));

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(jsonPath("$.assignments.length()").value(0))
                .andExpect(jsonPath("$.stops.length()").value(0));
    }

    @Test
    @DisplayName("an over-volume order is rejected")
    void overVolumeIsRejected() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("VOLUME", "10000", "5", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "6", "1", "READY_FOR_PLANNING");

        assign(trip, order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("volume 6")));
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
    }

    @Test
    @DisplayName("an over-pallet order is rejected")
    void overPalletIsRejected() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("PALLET", "10000", "50", 2));
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "1", "3", "READY_FOR_PLANNING");

        assign(trip, order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("pallets 3")));
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
    }

    @Test
    @DisplayName("a zero-pallet vehicle refuses any pallet and reports no percentage instead of dividing by zero")
    void zeroPalletVehicleIsARealLimit() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("TANKER", "5000", "20", 0));
        String palletised = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        String bulk = order(COMPANY_A, originA, destinationA1, date, "100", "1", "0", "READY_FOR_PLANNING");

        assign(trip, palletised).andExpect(status().isConflict());
        assign(trip, bulk)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.capacity.pallets.limit").value(0))
                .andExpect(jsonPath("$.trip.capacity.pallets.unlimited").value(false))
                .andExpect(jsonPath("$.trip.capacity.pallets.percentUsed").doesNotExist())
                .andExpect(jsonPath("$.trip.capacity.withinCapacity").value(true));
    }

    @Test
    @DisplayName("a trip with no vehicle is unlimited, and says so rather than reporting an empty bar")
    void tripWithoutVehicleIsUnlimited() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, null);
        String heavy = order(COMPANY_A, originA, destinationA1, date, "99999", "999", "99", "READY_FOR_PLANNING");

        assign(trip, heavy)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.capacity.source").value("NONE"))
                .andExpect(jsonPath("$.trip.capacity.weight.unlimited").value(true))
                .andExpect(jsonPath("$.trip.capacity.weight.limit").doesNotExist())
                .andExpect(jsonPath("$.trip.capacity.weight.percentUsed").doesNotExist())
                .andExpect(jsonPath("$.trip.capacity.withinCapacity").value(true));
    }

    @Test
    @DisplayName("swapping in a smaller vehicle is refused and the trip keeps the vehicle it had")
    void smallerVehicleIsRejected() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String big = vehicle("SWAP-BIG", "10000", "40", 20);
        String small = vehicle("SWAP-SMALL", "1000", "5", 2);
        String trip = newTrip(run, big);
        String order = order(COMPANY_A, originA, destinationA1, date, "5000", "10", "5", "READY_FOR_PLANNING");
        long version = versionOf(assign(trip, order).andExpect(status().isOk()));

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(small, departure(date), version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("weight 5000")));

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(jsonPath("$.trip.vehicleId").value(big))
                .andExpect(jsonPath("$.assignments.length()").value(1));
    }

    // --- vehicle double-booking -----------------------------------------------------

    @Test
    @DisplayName("a vehicle already booked on another active trip the same planning date is refused")
    void doubleBookingVehicleOnCreateIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String busyVehicle = vehicle("BOOKED", "10000", "40", 20);
        newTrip(run, busyVehicle);

        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":0}
                                """.formatted(busyVehicle, departure(date))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already booked")));
    }

    @Test
    @DisplayName("swapping a trip's vehicle into one already booked the same day is refused, and the trip keeps its own vehicle")
    void doubleBookingVehicleOnSwapIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String busyVehicle = vehicle("BUSY-SWAP", "10000", "40", 20);
        String ownVehicle = vehicle("OWN-SWAP", "10000", "40", 20);
        newTrip(run, busyVehicle);
        String trip = newTrip(run, ownVehicle);
        long version = versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A)));

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(busyVehicle, departure(date), version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already booked")));

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(jsonPath("$.trip.vehicleId").value(ownVehicle));
    }

    @Test
    @DisplayName("re-submitting a trip's own vehicle (e.g. only changing the departure time) never double-booking-conflicts with itself")
    void reassigningATripsOwnVehicleIsNotADoubleBooking() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String ownVehicle = vehicle("SELF-SWAP", "10000", "40", 20);
        String trip = newTrip(run, ownVehicle);
        long version = versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A)));

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(ownVehicle, departure(date, 10), version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.vehicleId").value(ownVehicle));
    }

    @Test
    @DisplayName("the same vehicle may run trips on two different planning runs/dates without conflict")
    void sameVehicleDifferentPlanningDateIsAllowed() throws Exception {
        String sharedVehicle = vehicle("MULTI-DAY", "10000", "40", 20);
        Run runOne = newRun(nextDate());
        newTrip(runOne, sharedVehicle);

        Run runTwo = newRun(nextDate());
        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + runTwo.id + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":0}
                                """.formatted(sharedVehicle, departure(runTwo.date()))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("cancelling a trip frees its vehicle to be booked again the same planning date")
    void cancellingATripFreesItsVehicleForRebooking() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String sharedVehicle = vehicle("CANCEL-FREE", "10000", "40", 20);
        String trip = newTrip(run, sharedVehicle);
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/cancel"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":0}
                                """.formatted(sharedVehicle, departure(date))))
                .andExpect(status().isCreated());
    }

    // --- moving between trips -----------------------------------------------------

    @Test
    @DisplayName("moving an order to a trip with room succeeds atomically and keeps the order planned")
    void moveSucceedsWhenTargetHasRoom() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String source = newTrip(run, vehicle("MOVE-SRC", "10000", "40", 20));
        String target = newTrip(run, vehicle("MOVE-TGT", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "2", "1", "READY_FOR_PLANNING");
        assign(source, order).andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + source + "/assignments/" + order + "/move"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTripId\":\"" + target + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(0))
                .andExpect(jsonPath("$.stops.length()").value(0));

        mockMvc.perform(asAdmin(get(TRIPS + "/" + target), COMPANY_A))
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].orderId").value(order))
                .andExpect(jsonPath("$.stops.length()").value(1));

        assertThat(orderStatus(order)).as("a moved order changes trip, it does not return to the pool").isEqualTo("PLANNED");
        assertThat(assignmentRows(order)).as("the closed assignment is kept as history").isEqualTo(2);
        assertThat(activeAssignmentRows(order)).isOne();
    }

    @Test
    @DisplayName("a move the target cannot take is refused and leaves the source untouched")
    void rejectedMoveLeavesTheSourceUnchanged() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String source = newTrip(run, vehicle("REJ-SRC", "10000", "40", 20));
        String target = newTrip(run, vehicle("REJ-TGT", "1000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "5000", "2", "1", "READY_FOR_PLANNING");
        assign(source, order).andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + source + "/assignments/" + order + "/move"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTripId\":\"" + target + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(asAdmin(get(TRIPS + "/" + source), COMPANY_A))
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].orderId").value(order))
                .andExpect(jsonPath("$.stops.length()").value(1));
        mockMvc.perform(asAdmin(get(TRIPS + "/" + target), COMPANY_A))
                .andExpect(jsonPath("$.assignments.length()").value(0));
        assertThat(activeAssignmentRows(order)).isOne();
        assertThat(assignmentRows(order)).as("a refused move writes no history either").isOne();
    }

    // --- concurrency --------------------------------------------------------------

    @Test
    @DisplayName("two planners assigning the same order to different trips: exactly one wins, the other gets a conflict")
    void concurrentAssignmentProducesExactlyOneAssignment() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripOne = newTrip(run, vehicle("RACE-1", "10000", "40", 20));
        String tripTwo = newTrip(run, vehicle("RACE-2", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "1", "1", "READY_FOR_PLANNING");

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = executor.invokeAll(List.of(
                    assignConcurrently(tripOne, order, startTogether),
                    assignConcurrently(tripTwo, order, startTogether)));
            List<Integer> statuses = List.of(results.get(0).get(30, TimeUnit.SECONDS),
                    results.get(1).get(30, TimeUnit.SECONDS));

            assertThat(statuses).as("exactly one assignment may succeed").containsOnlyOnce(200);
            // The loser is refused either way, and which way depends on how the two interleaved:
            // 409 when it got past its own eligibility check and the partial unique index caught
            // the insert, 400 when the winner had already committed and the order was no longer
            // eligible by the time it looked. What must never happen is a second success.
            assertThat(statuses.stream().filter(code -> code == 409 || code == 400).count())
                    .as("the loser is refused, never silently ignored").isOne();
        } finally {
            executor.shutdownNow();
        }

        assertThat(activeAssignmentRows(order)).as("the database holds exactly one open assignment").isOne();
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("assigning an order that is already on a trip is refused with a message that points at the move")
    void doubleAssignmentIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("DOUBLE", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, order).andExpect(status().isOk());

        assign(trip, order)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ready for planning")));
        assertThat(activeAssignmentRows(order)).isOne();
    }

    // --- tenancy and scope --------------------------------------------------------

    @Test
    @DisplayName("an order of another company, another origin or another date cannot be assigned")
    void outOfScopeOrdersAreRejected() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("SCOPE", "10000", "40", 20));

        String otherCompany = order(COMPANY_B, originB, destinationB, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, otherCompany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("in this company")));

        String otherOrigin = order(COMPANY_A, originA2, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, otherOrigin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different origin")));

        String otherDate = order(COMPANY_A, originA, destinationA1, date.plusDays(1), "100", "1", "1",
                "READY_FOR_PLANNING");
        assign(trip, otherDate)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("service date")));

        assertThat(activeAssignmentRows(otherCompany)).isZero();
        assertThat(activeAssignmentRows(otherOrigin)).isZero();
        assertThat(activeAssignmentRows(otherDate)).isZero();
    }

    @Test
    @DisplayName("a planning run of another company is not found, never forbidden")
    void crossCompanyRunIsNotFound() throws Exception {
        Run run = newRun(nextDate());

        mockMvc.perform(asAdmin(get(PLANNING + "/runs/" + run.id), COMPANY_B))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a read-only role may open the board but not change it")
    void viewerCannotManagePlanning() throws Exception {
        Run run = newRun(nextDate());

        mockMvc.perform(asViewer(get(PLANNING + "/runs"), COMPANY_A)).andExpect(status().isOk());
        mockMvc.perform(asViewer(get(PLANNING + "/runs/" + run.id), COMPANY_A)).andExpect(status().isOk());
        mockMvc.perform(asViewer(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(nextDate())))
                .andExpect(status().isForbidden());
        mockMvc.perform(asViewer(post(PLANNING + "/runs/" + run.id + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    // --- removal, stops, run lifecycle --------------------------------------------

    @Test
    @DisplayName("removing an order releases it back to the pool and keeps the assignment as history")
    void removalReleasesTheOrder() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("REMOVE", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, order).andExpect(status().isOk());

        mockMvc.perform(asAdmin(delete(TRIPS + "/" + trip + "/assignments/" + order), COMPANY_A)
                        .param("reason", "customer cancelled the pickup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(0))
                .andExpect(jsonPath("$.stops.length()").value(0))
                .andExpect(jsonPath("$.trip.capacity.weight.used").value(0));

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertThat(assignmentRows(order)).isOne();
        assertThat(activeAssignmentRows(order)).isZero();
        assertThat(removalReason(order)).isEqualTo("customer cancelled the pickup");
    }

    @Test
    @DisplayName("stops follow assignments, keep the planner's ordering, and refuse an ordering that invents a stop")
    void stopsFollowAssignmentsAndCanBeReordered() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("STOPS", "10000", "40", 20));
        String first = orderWithWindow(originA, destinationA1, date, "100", "08:00", "12:00");
        String second = orderWithWindow(originA, destinationA2, date, "100", "09:00", "17:00");
        String alsoFirstDestination = orderWithWindow(originA, destinationA1, date, "100", "07:00", "10:00");

        assign(trip, first).andExpect(status().isOk());
        assign(trip, second).andExpect(status().isOk());
        String board = assign(trip, alsoFirstDestination)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[0].orderCount").value(2))
                .andReturn().getResponse().getContentAsString();

        // The stop's window is the envelope of the two orders delivered there: earliest requested
        // start, latest requested end. Asserted against what the API itself reports for those
        // orders rather than against wall-clock literals - the fixture writes its times through
        // raw SQL while the application reads them through Hibernate under
        // `hibernate.jdbc.time_zone: UTC`, so only the two values' relationship is meaningful here
        // (an order written through the API round-trips unchanged - see
        // OrderApiIntegrationTest.createComputesTotalsAndReadsBack's window assertions).
        List<String> starts = JsonPath.read(board,
                "$.assignments[?(@.destinationId=='" + destinationA1 + "')].requestedWindowStart");
        List<String> ends = JsonPath.read(board,
                "$.assignments[?(@.destinationId=='" + destinationA1 + "')].requestedWindowEnd");
        assertThat(starts).hasSize(2);
        assertThat((String) JsonPath.read(board, "$.stops[0].serviceWindowStart"))
                .isEqualTo(starts.stream().min(String::compareTo).orElseThrow());
        assertThat((String) JsonPath.read(board, "$.stops[0].serviceWindowEnd"))
                .isEqualTo(ends.stream().max(String::compareTo).orElseThrow());

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/stops"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationIds\":[\"" + destinationA2 + "\",\"" + destinationA1 + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A2"))
                .andExpect(jsonPath("$.stops[0].sequence").value(1))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A1"));

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/stops"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationIds\":[\"" + destinationA2 + "\"]}"))
                .andExpect(status().isBadRequest());

        // ...and removing the last order of a destination drops that stop, keeping the other order.
        mockMvc.perform(asAdmin(delete(TRIPS + "/" + trip + "/assignments/" + second), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(1))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[0].sequence").value(1));
    }

    @Test
    @DisplayName("a second open run for the same origin and date is refused")
    void duplicateOpenRunIsRefused() throws Exception {
        LocalDate date = nextDate();
        newRun(date);

        mockMvc.perform(asAdmin(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(runRequest(date)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @DisplayName("cancelling a draft run cancels its trips and returns every order to the pool")
    void cancellingARunReleasesEveryOrder() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("CANCELRUN", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, order).andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id + "/cancel"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"weather\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CANCELLED"))
                .andExpect(jsonPath("$.trips[0].status").value("CANCELLED"));

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertThat(activeAssignmentRows(order)).isZero();

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/assignments"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order + "\"}"))
                .andExpect(status().isConflict());
    }

    // --- confirmation -------------------------------------------------------------

    @Test
    @DisplayName("confirmation refuses a trip without a vehicle, without a departure or without orders")
    void confirmationRefusesAnIncompleteTrip() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, null);

        confirm(run).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no vehicle")));

        String vehicle = vehicle("CONFIRM-INC", "10000", "40", 20);
        long version = versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A)));
        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
        confirm(run).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("planned departure")));

        version = versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A)));
        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(vehicle, departure(date), version)))
                .andExpect(status().isOk());
        confirm(run).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no orders")));
    }

    @Test
    @DisplayName("confirming freezes each trip's capacity, locks the plan and survives a later fleet change")
    void confirmationFreezesCapacityAndLocks() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String vehicleType = vehicleType("FREEZE", "10000", "40", 20);
        String vehicle = vehicleOfType("FREEZE", vehicleType);
        String trip = newTrip(run, vehicle);
        String order = order(COMPANY_A, originA, destinationA1, date, "5000", "10", "10", "READY_FOR_PLANNING");
        long version = versionOf(assign(trip, order).andExpect(status().isOk()));
        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(vehicle, departure(date), version)))
                .andExpect(status().isOk());

        confirm(run)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.trips[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.trips[0].capacity.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.trips[0].capacity.weight.limit").value(10000.00));

        // A confirmed plan is locked: no assignment, no vehicle change, no cancellation, no
        // second confirmation.
        String another = order(COMPANY_A, originA, destinationA1, date, "10", "1", "1", "READY_FOR_PLANNING");
        assign(trip, another).andExpect(status().isConflict());
        mockMvc.perform(asAdmin(delete(TRIPS + "/" + trip + "/assignments/" + order), COMPANY_A))
                .andExpect(status().isConflict());
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/cancel"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
        confirm(run).andExpect(status().isConflict());

        // The snapshot is what makes the confirmed plan auditable: shrinking the vehicle type
        // afterwards does not rewrite what the trip was validated against.
        execute("UPDATE tms.vehicle_type SET max_weight_kg = 500 WHERE id = '" + vehicleType + "'");
        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip + "/capacity"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.weight.limit").value(10000.00))
                .andExpect(jsonPath("$.withinCapacity").value(true));
    }

    @Test
    @DisplayName("confirmation refuses a trip whose vehicle stopped being available in the meantime")
    void confirmationRefusesAnUnavailableVehicle() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String vehicle = vehicle("MAINT", "10000", "40", 20);
        String trip = newTrip(run, vehicle);
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        long version = versionOf(assign(trip, order).andExpect(status().isOk()));
        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(vehicle, departure(date), version)))
                .andExpect(status().isOk());

        execute("UPDATE tms.vehicle SET availability_status = 'IN_MAINTENANCE' WHERE id = '" + vehicle + "'");

        confirm(run).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("no longer active and available")));
    }

    @Test
    @DisplayName("a stale version is refused: the second planner is told to reload rather than overwriting")
    void staleVersionIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("STALE", "10000", "40", 20));
        String order = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, order).andExpect(status().isOk()); // bumps the trip's version

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":0}
                                """.formatted(vehicle("STALE-2", "10000", "40", 20), departure(date))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("changed by someone else")));
    }

    @Test
    @DisplayName("the board costs the same number of queries whatever the fleet size (no N+1 on stops)")
    void boardQueryCountDoesNotGrowWithTheNumberOfTrips() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long oneTrip = statementsToRenderBoard(1);
            long fiveTrips = statementsToRenderBoard(5);

            // Every per-trip fact the board shows - load, capacity, vehicle, stop count - is
            // resolved by a grouped or batched query, so four extra trips must cost nothing.
            // Before TripViewAssembler counted stops in one query, each extra trip triggered the
            // lazy Trip.stops() collection and cost exactly one more statement.
            assertThat(fiveTrips)
                    .as("rendering a board of 5 trips took %d statements against %d for a board of 1: "
                            + "the per-trip cost is back", fiveTrips, oneTrip)
                    .isEqualTo(oneTrip);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    /**
     * Opens a run with {@code tripCount} trips, each carrying one order (so each has a stop), and
     * returns the number of JDBC statements the single board read costs.
     */
    private long statementsToRenderBoard(int tripCount) throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        for (int i = 0; i < tripCount; i++) {
            String trip = newTrip(run, vehicle("BOARD" + tripCount + "-" + i, "20000", "80", 40));
            assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                    .andExpect(status().isOk());
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(asAdmin(get(PLANNING + "/runs/" + run.id()), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips.length()").value(tripCount))
                .andExpect(jsonPath("$.trips[0].stopCount").value(1));
        return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("confirming a run while an order is moved between its trips never fails with a server error")
    void confirmDoesNotDeadlockAgainstAConcurrentMove() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripOne = newTrip(run, vehicle("LOCK-1", "20000", "80", 40));
        String tripTwo = newTrip(run, vehicle("LOCK-2", "20000", "80", 40));
        String moving = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(tripOne, moving).andExpect(status().isOk());
        assign(tripTwo, order(COMPANY_A, originA, destinationA2, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());

        // Confirm walks the run's trips; the move walks two of them. Both take the same row locks,
        // and before both were ordered by trip id one of the two orders was by trip number - the
        // classic ABBA deadlock, which PostgreSQL resolves by killing a transaction and which
        // reached the caller as a 500. Either request may legitimately lose to the other with a
        // 409 or a 404; neither may ever be a server error.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Integer> confirming = pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                return mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id() + "/confirm"), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> moving2 = pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                return mockMvc.perform(asAdmin(
                                post(TRIPS + "/" + tripOne + "/assignments/" + moving + "/move"), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetTripId\":\"" + tripTwo + "\"}"))
                        .andReturn().getResponse().getStatus();
            });

            assertThat(List.of(confirming.get(30, TimeUnit.SECONDS), moving2.get(30, TimeUnit.SECONDS)))
                    .as("a lock conflict must surface as a conflict, never as a server error")
                    .allSatisfy(statusCode -> assertThat(statusCode).isLessThan(500));
        } finally {
            pool.shutdownNow();
        }
    }

    // --- shipment header, stops and the route relationship (Job 07) ---------------

    @Test
    @DisplayName("a planned shipment exposes its whole header: number, plan, origin, carrier, unit, plate, type")
    void shipmentHeaderIsComplete() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String vehicleTypeId = vehicleType("HEADER", "10000", "40", 20);
        String trip = newTrip(run, vehicleOfType("HEADER", vehicleTypeId));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "2500", "10", "5", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.shipmentNumber").value(org.hamcrest.Matchers.startsWith("SH-")))
                .andExpect(jsonPath("$.trip.companyId").value(COMPANY_A.toString()))
                .andExpect(jsonPath("$.trip.planNumber").value(org.hamcrest.Matchers.startsWith("PL-")))
                .andExpect(jsonPath("$.trip.planningDate").value(date.toString()))
                .andExpect(jsonPath("$.trip.tripNumber").value(1))
                .andExpect(jsonPath("$.trip.status").value("DRAFT"))
                .andExpect(jsonPath("$.trip.originId").value(originA))
                .andExpect(jsonPath("$.trip.originCode").value("ORIGIN-A"))
                .andExpect(jsonPath("$.trip.carrierId").value(carrierA))
                // Resolved from the trip's own carrier_id, not from the vehicle - see below.
                .andExpect(jsonPath("$.trip.carrierName").value("Carrier A"))
                .andExpect(jsonPath("$.trip.vehicleCode").exists())
                .andExpect(jsonPath("$.trip.vehicleLicensePlate").exists())
                .andExpect(jsonPath("$.trip.vehicleTypeCode").exists())
                .andExpect(jsonPath("$.trip.plannedDepartureAt").exists())
                .andExpect(jsonPath("$.trip.capacity.source").value("LIVE"))
                .andExpect(jsonPath("$.trip.capacity.weight.used").value(2500.000))
                .andExpect(jsonPath("$.trip.capacity.weight.percentUsed").value(25.0))
                .andExpect(jsonPath("$.trip.orderCount").value(1))
                .andExpect(jsonPath("$.trip.stopCount").value(1))
                .andExpect(jsonPath("$.trip.routeId").doesNotExist());
    }

    @Test
    @DisplayName("the carrier on a shipment is the one it was planned with, even after the vehicle changes carrier")
    void carrierIsTheOneThePlanNamedNotTheVehiclesCurrentOne() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String vehicleId = vehicle("CARRIER-MOVE", "10000", "40", 20);
        String trip = newTrip(run, vehicleId);
        String otherCarrier = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name,"
                + " tax_id_type, tax_id_value) VALUES ('" + COMPANY_A + "', 'CARR-A2', 'Carrier A2', 'RUC',"
                + " '20100000002')");

        // The fleet master moves the truck to another carrier after the plan named the first one.
        execute("UPDATE tms.vehicle SET carrier_id = '" + otherCarrier + "' WHERE id = '" + vehicleId + "'");

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.carrierId").value(carrierA))
                .andExpect(jsonPath("$.trip.carrierName").value("Carrier A"));
    }

    @Test
    @DisplayName("stops come back map-ready, and a destination nobody geocoded reports no coordinate rather than a wrong one")
    void stopsCarryCoordinatesWhenTheDestinationHasThem() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("GEO", "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        assign(trip, order(COMPANY_A, originA, destinationA2, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[0].latitude").value(-12.046374))
                .andExpect(jsonPath("$.stops[0].longitude").value(-77.042793))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A2"))
                .andExpect(jsonPath("$.stops[1].latitude").doesNotExist())
                .andExpect(jsonPath("$.stops[1].longitude").doesNotExist());
    }

    @Test
    @DisplayName("applying a route reorders the shipment's stops without creating or dropping one")
    void applyingARouteReordersTheStops() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-APPLY", "10000", "40", 20));
        // Assigned A1 first, so the natural stop order is A1, A2 - the reverse of ROUTE-A's.
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        long version = versionOf(
                assign(trip, order(COMPANY_A, originA, destinationA2, date, "100", "1", "1", "READY_FOR_PLANNING"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1")));

        applyRoute(trip, routeA, true, version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.routeId").value(routeA))
                .andExpect(jsonPath("$.trip.routeCode").value("ROUTE-A"))
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A2"))
                .andExpect(jsonPath("$.stops[0].sequence").value(1))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[1].sequence").value(2));
    }

    @Test
    @DisplayName("recording a route without applying its sequence leaves the planner's stop order alone")
    void recordingARouteWithoutItsSequenceKeepsTheOrder() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-NOSEQ", "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        long version = versionOf(
                assign(trip, order(COMPANY_A, originA, destinationA2, date, "100", "1", "1", "READY_FOR_PLANNING"))
                        .andExpect(status().isOk()));

        applyRoute(trip, routeA, false, version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.routeId").value(routeA))
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A1"))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A2"));
    }

    @Test
    @DisplayName("a route is a suggestion: editing the master afterwards never rewrites a shipment's stops")
    void editingTheRouteMasterDoesNotRewriteTheShipment() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-DRIFT", "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        long version = versionOf(
                assign(trip, order(COMPANY_A, originA, destinationA2, date, "100", "1", "1", "READY_FOR_PLANNING"))
                        .andExpect(status().isOk()));
        applyRoute(trip, routeA, true, version).andExpect(status().isOk());

        // The corridor is re-sequenced in masterdata. The shipment stopped being a copy of it the
        // moment it was planned, and must not silently follow.
        execute("UPDATE tms.route_stop SET sequence = sequence + 10 WHERE route_id = '" + routeA + "'");

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].destinationCode").value("DEST-A2"))
                .andExpect(jsonPath("$.stops[1].destinationCode").value("DEST-A1"));

        // Restore the fixture: this class shares its route masters across tests.
        execute("UPDATE tms.route_stop SET sequence = sequence - 10 WHERE route_id = '" + routeA + "'");
    }

    @Test
    @DisplayName("a route from another origin, an inactive one, or one of another company is refused")
    void anIneligibleRouteIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-BAD", "10000", "40", 20));

        applyRoute(trip, routeOtherOriginA, true, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different origin")));
        // Another company's route is indistinguishable from one that does not exist.
        applyRoute(trip, routeB, true, 0).andExpect(status().isBadRequest());

        execute("UPDATE tms.route SET active = false WHERE id = '" + routeA + "'");
        try {
            applyRoute(trip, routeA, true, 0).andExpect(status().isBadRequest());
        } finally {
            execute("UPDATE tms.route SET active = true WHERE id = '" + routeA + "'");
        }
    }

    @Test
    @DisplayName("clearing a shipment's route keeps every stop it had")
    void clearingTheRouteKeepsTheStops() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-CLEAR", "10000", "40", 20));
        long version = versionOf(
                assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                        .andExpect(status().isOk()));
        version = versionOf(applyRoute(trip, routeA, true, version).andExpect(status().isOk()));

        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/route"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":null,\"applySequence\":false,\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.routeId").doesNotExist())
                .andExpect(jsonPath("$.stops.length()").value(1));
    }

    @Test
    @DisplayName("two planners applying a route to the same shipment: the second is told to reload")
    void concurrentRouteChangesDoNotBothWin() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-RACE", "10000", "40", 20));
        long version = versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Integer> attempt = () -> {
                barrier.await(15, TimeUnit.SECONDS);
                return mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/route"), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"routeId\":\"" + routeA + "\",\"applySequence\":true,\"version\":"
                                        + version + "}"))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(statuses).as("both planners held version %d; only one may write it", version)
                    .containsOnlyOnce(200);
            assertThat(statuses).allSatisfy(statusCode -> assertThat(statusCode).isLessThan(500));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a planned departure that falls on another day than the run's planning date is refused")
    void aDepartureOnAnotherDayIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);

        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id() + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":0}
                                """.formatted(vehicle("WRONG-DAY", "10000", "40", 20), departure(date.plusDays(3)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("planning date")));

        String trip = newTrip(run, vehicle("WRONG-DAY-SWAP", "10000", "40", 20));
        long version = versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A)));
        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(vehicle("WRONG-DAY-2", "10000", "40", 20), departure(date.minusDays(1)),
                                version)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a departure late in the local evening still belongs to the planning day, not to UTC's next one")
    void aLateLocalDepartureIsStillThePlanningDay() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);

        // 22:00 in America/Lima is already 03:00 the following day in UTC. The company's zone is
        // what decides, or the last hours of every planning day would be unusable.
        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id() + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":0}
                                """.formatted(vehicle("LATE-DAY", "10000", "40", 20), departure(date, 22))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("confirmation refuses a shipment carrying an order that was rescheduled after it was assigned")
    void confirmationRevalidatesTheOrdersItCarries() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("RESCHED", "10000", "40", 20));
        String rescheduled = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, rescheduled).andExpect(status().isOk());

        // The order moves to another service day while it sits on the draft trip. Confirming would
        // otherwise dispatch it on the run's day.
        execute("UPDATE tms.transport_order SET service_date = '" + date.plusDays(2) + "' WHERE id = '"
                + rescheduled + "'");

        confirm(run).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("service date")));
    }

    @Test
    @DisplayName("a confirmed shipment keeps its number and reports its frozen capacity as a snapshot")
    void aConfirmedShipmentKeepsItsIdentity() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("CONF-ID", "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        String before = JsonPath.read(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andReturn().getResponse().getContentAsString(), "$.trip.shipmentNumber");

        confirm(run).andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(jsonPath("$.trip.shipmentNumber").value(before))
                .andExpect(jsonPath("$.trip.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.trip.capacity.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.trip.capacity.weight.limit").value(10000.00));
    }

    @Test
    @DisplayName("confirming a trip writes exactly one SHIPMENT_CONFIRMED outbox row for it")
    void confirmationWritesAShipmentOutboxEvent() throws Exception {
        // The transactional-outbox proof behind job 08's outbound Shipment integration
        // (docs/integrations/OUTBOUND_SHIPMENT_V1.md): tms.shipment_outbox_event gets its row in
        // the very transaction PlanningRunService.confirmTrip commits, not afterwards.
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("OUTBOX", "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        String shipmentNumber = JsonPath.read(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andReturn().getResponse().getContentAsString(), "$.trip.shipmentNumber");

        confirm(run).andExpect(status().isOk());

        assertThat(queryLong("SELECT count(*) FROM tms.shipment_outbox_event WHERE trip_id = '" + trip + "'"))
                .isEqualTo(1);
        assertThat(queryString("SELECT event_type FROM tms.shipment_outbox_event WHERE trip_id = '" + trip + "'"))
                .isEqualTo("SHIPMENT_CONFIRMED");
        assertThat(queryString("SELECT shipment_number FROM tms.shipment_outbox_event WHERE trip_id = '" + trip + "'"))
                .isEqualTo(shipmentNumber);
        assertThat(queryString(
                "SELECT company_id::text FROM tms.shipment_outbox_event WHERE trip_id = '" + trip + "'"))
                .isEqualTo(COMPANY_A.toString());
    }

    @Test
    @DisplayName("every shipment number is distinct, including across planning runs")
    void shipmentNumbersAreUnique() throws Exception {
        Run runOne = newRun(nextDate());
        Run runTwo = newRun(nextDate());
        List<String> numbers = new java.util.ArrayList<>();
        for (Run run : List.of(runOne, runOne, runTwo)) {
            String trip = newTrip(run, vehicle("SHIP-NUM", "10000", "40", 20));
            numbers.add(JsonPath.read(mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                    .andReturn().getResponse().getContentAsString(), "$.trip.shipmentNumber"));
        }

        assertThat(numbers).doesNotHaveDuplicates().allMatch(number -> number.startsWith("SH-"));
    }

    @Test
    @DisplayName("a shipment cannot be pointed at a route through another company's board")
    void routeOfAnotherCompanyIsNotReachable() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("ROUTE-TENANT", "10000", "40", 20));

        // Company B's admin cannot even see the trip; company A's cannot see company B's route.
        mockMvc.perform(asAdmin(put(TRIPS + "/" + trip + "/route"), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeB + "\",\"applySequence\":false,\"version\":0}"))
                .andExpect(status().isNotFound());
        applyRoute(trip, routeB, false, 0).andExpect(status().isBadRequest());
    }

    // --- trip execution (V25, docs/domain/TRIP_EXECUTION_V1.md) --------------------

    @Test
    @DisplayName("a confirmed trip walks ready -> dispatched -> completed, recording an actual time at each step")
    void executionLifecycleRecordsActualTimes() throws Exception {
        String trip = confirmedTrip("EXEC-HAPPY");

        long version = versionOf(execute(trip, "ready", null, versionOfTrip(trip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.trip.readyAt").isNotEmpty()));
        version = versionOf(execute(trip, "dispatch", null, version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.trip.actualDepartureAt").isNotEmpty()));

        // V27 forbids closing a trip over stops nobody resolved, so the stops are walked first.
        // This is the happy path, so each one is arrived at and completed - what a driver actually
        // does - rather than skipped, which would need a typed reason and prove something else.
        for (String stopId : stopIdsOf(trip)) {
            stopAction(trip, stopId, "arrive").andExpect(status().isOk());
            stopAction(trip, stopId, "complete").andExpect(status().isOk());
        }

        // Re-read rather than reusing `version`: resolving the stops moved the trip on too.
        execute(trip, "complete", null, versionOfTrip(trip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.status").value("COMPLETED"))
                .andExpect(jsonPath("$.trip.actualCompletionAt").isNotEmpty())
                // Terminal: the UI renders its buttons from this, so an empty list is the contract.
                .andExpect(jsonPath("$.trip.allowedTransitions").isEmpty());

        // The plan is never rewritten by what happened - the whole point of two columns.
        assertThat(queryLong("SELECT count(*) FROM tms.trip WHERE id = '" + trip
                + "' AND planned_departure_at IS NOT NULL AND actual_departure_at IS NOT NULL"
                + " AND ready_by IS NOT NULL AND dispatched_by IS NOT NULL AND completed_by IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an illegal transition is refused with 409 naming both states, and changes nothing")
    void illegalTransitionIsRefused() throws Exception {
        String trip = confirmedTrip("EXEC-SKIP");

        // CONFIRMED -> IN_TRANSIT skips READY_FOR_DISPATCH.
        execute(trip, "dispatch", null, versionOfTrip(trip)).andExpect(status().isConflict());

        assertThat(queryString("SELECT status FROM tms.trip WHERE id = '" + trip + "'")).isEqualTo("CONFIRMED");
        assertThat(queryLong("SELECT count(*) FROM tms.shipment_outbox_event WHERE trip_id = '" + trip
                + "' AND event_type = 'SHIPMENT_DISPATCHED'")).isZero();
    }

    @Test
    @DisplayName("a retry of a transition that already succeeded is answered, not conflicted, and emits no second event")
    void executionIsIdempotent() throws Exception {
        String trip = confirmedTrip("EXEC-RETRY");
        execute(trip, "ready", null, versionOfTrip(trip)).andExpect(status().isOk());

        // Version 0 is stale by now - which is exactly the state a retried request is in.
        execute(trip, "ready", null, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.status").value("READY_FOR_DISPATCH"));

        assertThat(queryLong("SELECT count(*) FROM tms.shipment_outbox_event WHERE trip_id = '" + trip
                + "' AND event_type = 'SHIPMENT_READY'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a stale version on a state not yet reached is still refused with 409")
    void staleVersionIsStillRefused() throws Exception {
        String trip = confirmedTrip("EXEC-STALE");
        execute(trip, "ready", null, versionOfTrip(trip)).andExpect(status().isOk());

        execute(trip, "dispatch", null, 0).andExpect(status().isConflict());

        assertThat(queryString("SELECT status FROM tms.trip WHERE id = '" + trip + "'"))
                .isEqualTo("READY_FOR_DISPATCH");
    }

    @Test
    @DisplayName("an actual time in the future is refused with 400")
    void aFutureActualTimeIsRefused() throws Exception {
        String trip = confirmedTrip("EXEC-FUTURE");

        execute(trip, "ready", OffsetDateTime.now().plusDays(1).toString(), versionOfTrip(trip))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("each execution transition writes exactly one outbox event and one audit event")
    void everyTransitionIsPublishedAndAudited() throws Exception {
        String trip = confirmedTrip("EXEC-EVENTS");
        long version = versionOf(execute(trip, "ready", null, versionOfTrip(trip)).andExpect(status().isOk()));
        execute(trip, "dispatch", null, version).andExpect(status().isOk());

        assertThat(queryLong("SELECT count(*) FROM tms.shipment_outbox_event WHERE trip_id = '" + trip + "'"))
                .isEqualTo(3); // CONFIRMED, READY, DISPATCHED
        assertThat(queryLong("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + trip
                + "' AND action IN ('SHIPMENT_READY', 'SHIPMENT_DISPATCHED')")).isEqualTo(2);
    }

    @Test
    @DisplayName("cancelling a confirmed trip needs a reason, releases its orders and publishes SHIPMENT_CANCELLED")
    void cancellingAConfirmedTripPublishesAndReleases() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("EXEC-CANCEL", "10000", "40", 20));
        String orderId = order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING");
        assign(trip, orderId).andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());

        long version = versionOfTrip(trip);
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/cancel"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/cancel"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"reason\":\"Customer closed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.status").value("CANCELLED"));

        assertThat(orderStatus(orderId)).isEqualTo("READY_FOR_PLANNING");
        assertThat(queryLong("SELECT count(*) FROM tms.shipment_outbox_event WHERE trip_id = '" + trip
                + "' AND event_type = 'SHIPMENT_CANCELLED'")).isEqualTo(1);
        // Cancelled after confirmation, so it keeps both the confirmation and its capacity snapshot.
        assertThat(queryLong("SELECT count(*) FROM tms.trip WHERE id = '" + trip
                + "' AND confirmed_at IS NOT NULL AND capacity_snapshot_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("a trip that has departed can no longer be cancelled")
    void aDepartedTripCannotBeCancelled() throws Exception {
        String trip = confirmedTrip("EXEC-GONE");
        long version = versionOf(execute(trip, "ready", null, versionOfTrip(trip)).andExpect(status().isOk()));
        execute(trip, "dispatch", null, version).andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/cancel"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + versionOfTrip(trip) + ",\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("execution endpoints refuse a caller without planning.trip:execute and a trip of another company")
    void executionIsScopedAndAuthorized() throws Exception {
        String trip = confirmedTrip("EXEC-AUTH");

        mockMvc.perform(asViewer(post(TRIPS + "/" + trip + "/ready"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        // Company B's admin holds the authority but not the trip: 404, never 403 - the existence
        // of another tenant's shipment is not something to confirm.
        mockMvc.perform(asAdmin(post(TRIPS + "/" + trip + "/ready"), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the trips list spans planning runs, is company-scoped and filters by status")
    void tripListSpansRunsAndFilters() throws Exception {
        String confirmed = confirmedTrip("LIST-A");
        LocalDate date = nextDate();
        Run otherRun = newRun(date);
        String draft = newTrip(otherRun, vehicle("LIST-B", "10000", "40", 20));

        String all = mockMvc.perform(asAdmin(get(TRIPS + "?size=200"), COMPANY_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(all, "$.content[*].id");
        assertThat(ids).contains(confirmed, draft);

        String drafts = mockMvc.perform(asAdmin(get(TRIPS + "?size=200&status=DRAFT"), COMPANY_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> draftIds = JsonPath.read(drafts, "$.content[*].id");
        assertThat(draftIds).contains(draft).doesNotContain(confirmed);

        // Another tenant's board never leaks in, whatever the filters say.
        String otherCompany = mockMvc.perform(asAdmin(get(TRIPS + "?size=200"), COMPANY_B))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> otherIds = JsonPath.read(otherCompany, "$.content[*].id");
        assertThat(otherIds).doesNotContain(confirmed, draft);
    }

    // --- helpers ------------------------------------------------------------------

    private record Run(String id, LocalDate date) {
    }

    /** A trip with one order on it, confirmed - the state every execution test starts from. */
    private String confirmedTrip(String vehicleCode) throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle(vehicleCode, "10000", "40", 20));
        assign(trip, order(COMPANY_A, originA, destinationA1, date, "100", "1", "1", "READY_FOR_PLANNING"))
                .andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        return trip;
    }

    private org.springframework.test.web.servlet.ResultActions execute(
            String tripId, String action, String occurredAt, long version) throws Exception {
        String body = occurredAt == null
                ? "{\"version\":" + version + "}"
                : "{\"version\":" + version + ",\"occurredAt\":\"" + occurredAt + "\"}";
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/" + action), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** The stops of a trip in sequence, as its detail view reports them. */
    private java.util.List<String> stopIdsOf(String tripId) throws Exception {
        String body = mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId), COMPANY_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.stops[*].id");
    }

    /** One stop-execution transition. The body is empty: occurredAt and notes are both optional. */
    private org.springframework.test.web.servlet.ResultActions stopAction(
            String tripId, String stopId, String action) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/stops/" + stopId + "/" + action), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private long versionOfTrip(String tripId) throws Exception {
        return versionOf(mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId), COMPANY_A))
                .andExpect(status().isOk()));
    }

    private Callable<Integer> assignConcurrently(String tripId, String orderId, CyclicBarrier barrier) {
        return () -> {
            barrier.await(15, TimeUnit.SECONDS);
            return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\"}"))
                    .andReturn().getResponse().getStatus();
        };
    }

    private org.springframework.test.web.servlet.ResultActions applyRoute(
            String tripId, String routeId, boolean applySequence, long version) throws Exception {
        return mockMvc.perform(asAdmin(put(TRIPS + "/" + tripId + "/route"), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeId\":\"" + routeId + "\",\"applySequence\":" + applySequence
                        + ",\"version\":" + version + "}"));
    }

    // --- split allocation (migration V37) ------------------------------------------

    /**
     * The case the whole of V37 exists for, and the one the brief names: one order, two trucks.
     */
    @Test
    @DisplayName("an order too big for one truck is split across two, and the ledger adds up")
    void anOrderIsSplitAcrossTwoTrips() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripA = newTrip(run, vehicle("SPL-A", "10000", "40", 100));
        String tripB = newTrip(run, vehicle("SPL-B", "10000", "40", 100));
        // 100 pallets. Neither truck is the problem here - the point is that the order is divisible.
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        // 70 on the first truck. The order is NOT planned yet: 30 pallets still need one.
        assignPart(tripA, order, "700", "7", "70")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].wholeOrder").value(false))
                .andExpect(jsonPath("$.trip.capacity.pallets.used").value(70.00));
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertThat(new java.math.BigDecimal(allocated(order, "pallets")))
                .isEqualByComparingTo("70");

        // Still in the pool, and the board says how much of it is left rather than repeating the
        // order's total - which is what stops a planner loading 100 more pallets onto truck two.
        mockMvc.perform(asAdmin(get(PLANNING + "/eligible-orders"), COMPANY_A)
                        .param("originId", originA).param("serviceDate", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + order + "')].pendingPallets").value(
                        org.hamcrest.Matchers.contains(30.00)))
                .andExpect(jsonPath("$.content[?(@.id=='" + order + "')].partiallyAllocated").value(
                        org.hamcrest.Matchers.contains(true)));

        // The remaining 30 on the second truck. Omitting the amounts means "the rest of it".
        assign(tripB, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.capacity.pallets.used").value(30.00));

        // Now it is planned, exactly once, and it left the pool.
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        assertThat(activeAssignmentRows(order)).isEqualTo(2);
        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("100");
        assertThat(new java.math.BigDecimal(allocated(order, "weight_kg"))).isEqualByComparingTo("1000");
        mockMvc.perform(asAdmin(get(PLANNING + "/eligible-orders"), COMPANY_A)
                        .param("originId", originA).param("serviceDate", date.toString()))
                .andExpect(jsonPath("$.content[?(@.id=='" + order + "')]").isEmpty());

        // The stored running total and the ledger it summarises agree - the invariant the column
        // exists to make constrainable.
        assertThat(new java.math.BigDecimal(allocated(order, "pallets")))
                .isEqualByComparingTo(new java.math.BigDecimal(ledgerSum(order, "pallets")));
        assertThat(new java.math.BigDecimal(allocated(order, "weight_kg")))
                .isEqualByComparingTo(new java.math.BigDecimal(ledgerSum(order, "weight_kg")));

        // And the order was never duplicated to achieve it.
        assertThat(queryLong("SELECT count(*) FROM tms.transport_order WHERE id = '" + order + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a split that would over-allocate the order is refused and changes nothing")
    void overAllocationIsRefused() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripA = newTrip(run, vehicle("OVR-A", "100000", "400", 1000));
        String tripB = newTrip(run, vehicle("OVR-B", "100000", "400", 1000));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        assignPart(tripA, order, "700", "7", "70").andExpect(status().isOk());

        // 40 more would make 110 of a 100-pallet order. Both trucks have room; the *order* does not.
        assignPart(tripB, order, "400", "4", "40")
                .andExpect(status().isConflict());

        assertThat(activeAssignmentRows(order)).isEqualTo(1);
        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("70");
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
    }

    @Test
    @DisplayName("an exact split fills the order to the pallet and plans it")
    void anExactSplitPlansTheOrder() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripA = newTrip(run, vehicle("EXA-A", "100000", "400", 1000));
        String tripB = newTrip(run, vehicle("EXA-B", "100000", "400", 1000));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        assignPart(tripA, order, "700", "7", "70").andExpect(status().isOk());
        // Trailing zeros: 30.00 and 30 are the same quantity, and a ledger comparing with equals
        // rather than compareTo would refuse the assignment that exactly finishes the order.
        assignPart(tripB, order, "300.000", "3.0000", "30.00").andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("removing one half of a split returns only that half to the pool")
    void removingHalfOfASplitReturnsOnlyThatHalf() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripA = newTrip(run, vehicle("REM-A", "100000", "400", 1000));
        String tripB = newTrip(run, vehicle("REM-B", "100000", "400", 1000));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        assignPart(tripA, order, "700", "7", "70").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PLANNED");

        mockMvc.perform(asAdmin(delete(TRIPS + "/" + tripB + "/assignments/" + order), COMPANY_A))
                .andExpect(status().isOk());

        // Back in the pool for the 30 that came off - not for the 70 still loaded on truck A.
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("70");
        assertThat(activeAssignmentRows(order)).isEqualTo(1);
        assertThat(new java.math.BigDecimal(allocated(order, "pallets")))
                .isEqualByComparingTo(new java.math.BigDecimal(ledgerSum(order, "pallets")));
    }

    @Test
    @DisplayName("the same order cannot be put on the same trip twice")
    void noTwoAssignmentsOfOneOrderOnOneTrip() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String trip = newTrip(run, vehicle("TWICE", "100000", "400", 1000));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        assignPart(trip, order, "700", "7", "70").andExpect(status().isOk());
        assignPart(trip, order, "300", "3", "30").andExpect(status().isConflict());

        assertThat(activeAssignmentRows(order)).isEqualTo(1);
        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("moving half a split carries that half, not the whole order")
    void movingASplitCarriesOnlyItsOwnShare() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripA = newTrip(run, vehicle("MOV-A", "100000", "400", 1000));
        String tripB = newTrip(run, vehicle("MOV-B", "100000", "400", 1000));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        assignPart(tripA, order, "700", "7", "70").andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(TRIPS + "/" + tripA + "/assignments/" + order + "/move"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTripId\":\"" + tripB + "\"}"))
                .andExpect(status().isOk());

        // The ledger is unchanged by a move: the same 70 pallets, on a different truck.
        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("70");
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertThat(queryLong("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + order
                + "' AND trip_id = '" + tripB + "' AND status = 'ACTIVE' AND NOT whole_order")).isEqualTo(1);
    }

    /**
     * The race the running total exists for. Two planners each read "nothing allocated", each
     * conclude there is room for 70 of the 100, and both insert. Exactly one may win.
     */
    @Test
    @DisplayName("two planners splitting the same order concurrently cannot both over-allocate it")
    void concurrentSplitsCannotOverAllocate() throws Exception {
        LocalDate date = nextDate();
        Run run = newRun(date);
        String tripA = newTrip(run, vehicle("RACE-A", "100000", "400", 1000));
        String tripB = newTrip(run, vehicle("RACE-B", "100000", "400", 1000));
        String order = order(COMPANY_A, originA, destinationA1, date, "1000", "10", "100", "READY_FOR_PLANNING");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> attemptSplit(ready, go, tripA, order));
            var second = pool.submit(() -> attemptSplit(ready, go, tripB, order));
            ready.await();
            go.countDown();
            int okCount = (first.get() == 200 ? 1 : 0) + (second.get() == 200 ? 1 : 0);
            assertThat(okCount)
                    .as("exactly one of two racing 70-pallet splits of a 100-pallet order may succeed")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(new java.math.BigDecimal(allocated(order, "pallets"))).isEqualByComparingTo("70");
        assertThat(activeAssignmentRows(order)).isEqualTo(1);
        // And the database itself never held an over-allocated row.
        assertThat(new java.math.BigDecimal(allocated(order, "pallets")))
                .isEqualByComparingTo(new java.math.BigDecimal(ledgerSum(order, "pallets")));
    }

    private int attemptSplit(java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch go,
            String tripId, String orderId) {
        try {
            ready.countDown();
            go.await();
            return assignPart(tripId, orderId, "700", "7", "70").andReturn().getResponse().getStatus();
        } catch (Exception failed) {
            // A lock timeout or a serialisation failure is a refusal, which is the outcome under
            // test - it is not a pass for the losing thread.
            return 409;
        }
    }

    private org.springframework.test.web.servlet.ResultActions assign(String tripId, String orderId) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\"}"));
    }

    /** Assigns only part of an order: the split V37 exists for. */
    private org.springframework.test.web.servlet.ResultActions assignPart(String tripId, String orderId,
            String weightKg, String volumeM3, String pallets) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"weightKg\":" + weightKg + ",\"volumeM3\":"
                        + volumeM3 + ",\"pallets\":" + pallets + "}"));
    }

    /** The order's own running total of what is on trucks (V37). */
    private static String allocated(String orderId, String measure) {
        return queryString("SELECT allocated_" + measure + " FROM tms.transport_order WHERE id = '" + orderId + "'");
    }

    /** The same figure recomputed from the ledger, for the consistency assertions. */
    private static String ledgerSum(String orderId, String measure) {
        return queryString("SELECT coalesce(sum(assigned_" + measure + "), 0) FROM tms.trip_order_assignment"
                + " WHERE order_id = '" + orderId + "' AND status = 'ACTIVE'");
    }

    private org.springframework.test.web.servlet.ResultActions confirm(Run run) throws Exception {
        return mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id + "/confirm"), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"));
    }

    private Run newRun(LocalDate date) throws Exception {
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(runRequest(date)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.planNumber").value(org.hamcrest.Matchers.startsWith("PL-")))
                .andExpect(jsonPath("$.run.status").value("DRAFT"))
                .andExpect(jsonPath("$.run.mode").value("MANUAL"))
                .andReturn().getResponse().getContentAsString();
        return new Run(JsonPath.read(response, "$.run.id"), date);
    }

    /** A run from a named origin, for the cases that need one with coordinates. */
    private Run newRunFrom(String originId, LocalDate date) throws Exception {
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + originId + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Run(JsonPath.read(response, "$.run.id"), date);
    }

    private static String runRequest(LocalDate date) {
        return "{\"originId\":\"" + originA + "\",\"planningDate\":\"" + date + "\"}";
    }

    private String newTrip(Run run, String vehicleId) throws Exception {
        String body = vehicleId == null
                ? "{\"version\":0}"
                : "{\"vehicleId\":\"" + vehicleId + "\",\"plannedDepartureAt\":\"" + departure(run.date())
                        + "\",\"version\":0}";
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run.id + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trip.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.trip.id");
    }

    private static long versionOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        Number version = JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.trip.version");
        return version.longValue();
    }

    /**
     * A planned departure that belongs to {@code date} in the fixture company's own time zone
     * ({@code America/Lima}, UTC-5): {@code ShipmentTimeRules} judges the departure day there, so
     * a literal instant shared by every test would now be on the wrong planning day for all but
     * one of them.
     */
    private static String departure(LocalDate date) {
        return departure(date, 8);
    }

    private static String departure(LocalDate date, int localHour) {
        return date.atTime(localHour, 0).atZone(java.time.ZoneId.of("America/Lima")).toOffsetDateTime().toString();
    }

    private static LocalDate nextDate() {
        return LocalDate.of(2026, 5, 1).plusDays(SEQUENCE.incrementAndGet());
    }

    private static String vehicle(String code, String maxWeightKg, String maxVolumeM3, int maxPallets) {
        return vehicleOfType(code, vehicleType(code, maxWeightKg, maxVolumeM3, maxPallets));
    }

    private static String vehicleType(String code, String maxWeightKg, String maxVolumeM3, int maxPallets) {
        int number = SEQUENCE.incrementAndGet();
        return insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY_A + "', 'TYPE-" + code + "-" + number + "', '"
                + code + " type', " + maxWeightKg + ", " + maxVolumeM3 + ", " + maxPallets + ")");
    }

    private static String vehicleOfType(String code, String vehicleTypeId) {
        int number = SEQUENCE.incrementAndGet();
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY_A + "', 'VEH-" + code + "-" + number + "', '"
                + String.format(java.util.Locale.ROOT, "PLT-%05d", number) + "', '" + carrierA + "', '" + vehicleTypeId
                + "')");
    }

    private static String order(UUID companyId, String originId, String destinationId, LocalDate serviceDate,
            String weightKg, String volumeM3, String pallets, String status) {
        return insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets) VALUES ('"
                + companyId + "', 'TO-PLN-" + String.format(java.util.Locale.ROOT, "%06d", SEQUENCE.incrementAndGet())
                + "', '" + originId + "', '" + destinationId + "', '" + serviceDate + "', '" + status + "', "
                + weightKg + ", " + volumeM3 + ", " + pallets + ")");
    }

    private static String orderWithWindow(String originId, String destinationId, LocalDate serviceDate,
            String weightKg, String windowStart, String windowEnd) {
        return insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets,"
                + " requested_window_start, requested_window_end) VALUES ('" + COMPANY_A + "', 'TO-PLN-"
                + String.format(java.util.Locale.ROOT, "%06d", SEQUENCE.incrementAndGet()) + "', '" + originId + "', '"
                + destinationId + "', '" + serviceDate + "', 'READY_FOR_PLANNING', " + weightKg + ", 1, 1, '"
                + windowStart + "', '" + windowEnd + "')");
    }

    private static String orderStatus(String orderId) {
        return queryString("SELECT status FROM tms.transport_order WHERE id = '" + orderId + "'");
    }

    private static String removalReason(String orderId) {
        return queryString("SELECT removal_reason FROM tms.trip_order_assignment WHERE order_id = '" + orderId + "'");
    }

    private static long assignmentRows(String orderId) {
        return queryLong("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + orderId + "'");
    }

    private static long activeAssignmentRows(String orderId) {
        return queryLong("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + orderId
                + "' AND status = 'ACTIVE'");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    /**
     * Seeds one canonical location holding one operational role. Since V23 an origin and a
     * destination are not records of their own - they are {@code tms.location} rows holding
     * {@code ORIGIN} or {@code DESTINATION} - and the role is what every assignment lookup
     * filters on, so a location seeded without one is invisible to the API under test.
     */
    private static String insertLocation(UUID companyId, String code, String name, String role) {
        return insertLocation(companyId, code, name, role, "", "");
    }

    /** {@link #insertLocation(UUID, String, String, String)} with extra columns, e.g. coordinates. */
    private static String insertLocation(UUID companyId, String code, String name, String role,
            String extraColumns, String extraValues) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name" + extraColumns
                + ") VALUES ('" + companyId + "', '" + code + "', '" + name + "'" + extraValues + ")");
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
            throw new IllegalStateException("could not seed a planning fixture", failed);
        }
    }

    private static String queryString(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the planning fixture", failed);
        }
    }

    private static long queryLong(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the planning fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the planning fixture", failed);
        }
    }
}
