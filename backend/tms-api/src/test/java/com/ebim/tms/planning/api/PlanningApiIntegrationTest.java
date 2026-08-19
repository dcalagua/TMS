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

        originA = insertReturningId(
                "INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_A + "', 'ORIGIN-A', 'Origin A')");
        originA2 = insertReturningId("INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_A
                + "', 'ORIGIN-A2', 'Origin A2')");
        destinationA1 = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_A + "', 'DEST-A1', 'Destination A1', 'PE')");
        destinationA2 = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_A + "', 'DEST-A2', 'Destination A2', 'PE')");
        carrierA = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY_A + "', 'CARR-A', 'Carrier A', 'RUC', '20100000001')");
        originB = insertReturningId(
                "INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_B + "', 'ORIGIN-B', 'Origin B')");
        destinationB = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_B + "', 'DEST-B', 'Destination B', 'PE')");
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
                                {"vehicleId":"%s","plannedDepartureAt":"2026-05-01T08:00:00Z","version":%d}
                                """.formatted(small, version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("weight 5000")));

        mockMvc.perform(asAdmin(get(TRIPS + "/" + trip), COMPANY_A))
                .andExpect(jsonPath("$.trip.vehicleId").value(big))
                .andExpect(jsonPath("$.assignments.length()").value(1));
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
                                {"vehicleId":"%s","plannedDepartureAt":"2026-05-01T08:00:00Z","version":%d}
                                """.formatted(vehicle, version)))
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
                                {"vehicleId":"%s","plannedDepartureAt":"2026-05-01T08:00:00Z","version":%d}
                                """.formatted(vehicle, version)))
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
                                {"vehicleId":"%s","plannedDepartureAt":"2026-05-01T08:00:00Z","version":%d}
                                """.formatted(vehicle, version)))
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
                                {"vehicleId":"%s","plannedDepartureAt":"2026-05-01T08:00:00Z","version":0}
                                """.formatted(vehicle("STALE-2", "10000", "40", 20))))
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

    // --- helpers ------------------------------------------------------------------

    private record Run(String id, LocalDate date) {
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

    private org.springframework.test.web.servlet.ResultActions assign(String tripId, String orderId) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"), COMPANY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\"}"));
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

    private static String runRequest(LocalDate date) {
        return "{\"originId\":\"" + originA + "\",\"planningDate\":\"" + date + "\"}";
    }

    private String newTrip(Run run, String vehicleId) throws Exception {
        String body = vehicleId == null
                ? "{\"version\":0}"
                : "{\"vehicleId\":\"" + vehicleId + "\",\"plannedDepartureAt\":\"2026-05-01T08:00:00Z\",\"version\":0}";
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
