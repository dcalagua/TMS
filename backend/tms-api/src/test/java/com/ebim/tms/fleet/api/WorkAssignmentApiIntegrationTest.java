package com.ebim.tms.fleet.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
 * A day's work, end to end (migration V47, debt D5).
 *
 * <p>What no unit test can show: that the API runs, that <b>another company cannot touch any of
 * it</b>, and - the reason this class exists - that <b>two dispatchers planning the same truck at
 * the same second produce exactly one day</b>. The sequencing arithmetic is proven without a
 * database in {@code WorkSequenceValidatorTest}; this proves the parts that need one.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WorkAssignmentApiIntegrationTest.JwtDecoderOverride.class)
class WorkAssignmentApiIntegrationTest {

    private static final String ASSIGNMENTS = "/api/v1/fleet/work-assignments";

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("99999999-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("99999999-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("99999999-0000-4000-8000-0000000000e2");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String vehicleA;
    private static String vehicleA2;
    private static String driverA;
    private static String driverA2;
    private static String tripB;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_work_assignment_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'WA-ORG', 'Work Assignment Org')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'WA-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'WA-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'wa.admin@example.invalid', 'WA Admin'), ('" + VIEWER_AUTH
                + "', 'wa.viewer@example.invalid', 'WA Viewer')");
        membership("wa.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("wa.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("wa.viewer@example.invalid", COMPANY_A, "VIEWER");

        vehicleA = vehicle(COMPANY_A, "VEH-A1");
        vehicleA2 = vehicle(COMPANY_A, "VEH-A2");
        driverA = driver(COMPANY_A, "DRV-A1");
        driverA2 = driver(COMPANY_A, "DRV-A2");
        tripB = trip(COMPANY_B, "ORIGIN-B");
    }

    private static String vehicle(UUID companyId, String code) {
        String type = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + code
                + "', 'Rigid', 10000, 40, 20) RETURNING id");
        return idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                + " VALUES ('" + companyId + "', '" + type + "', '" + code + "', 'PLT-" + code
                + "') RETURNING id");
    }

    private static String driver(UUID companyId, String code) {
        return idOf("INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type,"
                + " document_number, license_number) VALUES ('" + companyId + "', '" + code + "', 'A', 'B',"
                + " 'DNI', '" + Math.abs(code.hashCode()) % 100000000 + "', 'L-" + code + "') RETURNING id");
    }

    private static String trip(UUID companyId, String originCode) {
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', '" + originCode + "', 'Origin') RETURNING id");
        String run = idOf("INSERT INTO tms.planning_run (company_id, plan_number, origin_id,"
                + " planning_date, status) VALUES ('" + companyId + "', 'PL-" + originCode + "', '" + origin
                + "', '2026-09-07', 'DRAFT') RETURNING id");
        return idOf("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number)"
                + " VALUES ('" + companyId + "', '" + run + "', '2026-09-07', 1) RETURNING id");
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("INSERT INTO tms.membership (app_user_id, organization_id, company_id)"
                + " SELECT id, '" + ORGANIZATION + "', '" + companyId + "' FROM tms.app_user WHERE email = '"
                + email + "'");
        execute("INSERT INTO tms.membership_role (membership_id, role_id) SELECT m.id, r.id"
                + " FROM tms.membership m JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '"
                + email + "' JOIN tms.role r ON r.code = '" + roleCode + "' WHERE m.company_id = '"
                + companyId + "'");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    /** Each test uses its own date, so the one-day-per-vehicle index does not collide across tests. */
    private String nextDate() {
        return java.time.LocalDate.of(2026, 9, 1).plusDays(SEQUENCE.incrementAndGet()).toString();
    }

    private String body(String date, String vehicleId, String driverId, String... tripIds) {
        String trips = tripIds.length == 0 ? "[]"
                : "[" + String.join(",", java.util.Arrays.stream(tripIds).map(id -> "\"" + id + "\"").toList()) + "]";
        return "{\"operationalDate\":\"" + date + "\",\"vehicleId\":\"" + vehicleId + "\""
                + (driverId == null ? "" : ",\"driverId\":\"" + driverId + "\"")
                + ",\"tripIds\":" + trips + "}";
    }

    private String create(String date, String vehicleId, String driverId) throws Exception {
        String response = mockMvc.perform(asAdmin(post(ASSIGNMENTS), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(body(date, vehicleId, driverId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    // --- the day ---------------------------------------------------------------------

    @Nested
    @DisplayName("building a day")
    class Building {

        @Test
        @DisplayName("an empty day is legal: a truck is committed before it is filled")
        void emptyDayIsLegal() throws Exception {
            String id = create(nextDate(), vehicleA, driverA);

            mockMvc.perform(asAdmin(get(ASSIGNMENTS + "/" + id), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.feasible").value(true))
                    .andExpect(jsonPath("$.trips").isEmpty());
        }

        @Test
        @DisplayName("a day can be opened without a driver, and one named later")
        void driverCanBeNamedLater() throws Exception {
            String date = nextDate();
            String id = create(date, vehicleA, null);

            mockMvc.perform(asAdmin(put(ASSIGNMENTS + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(date, vehicleA, driverA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.driverId").value(driverA));
        }

        @Test
        @DisplayName("cancelling releases the vehicle for another day's work")
        void cancellingReleasesTheResource() throws Exception {
            String date = nextDate();
            String first = create(date, vehicleA, driverA);

            // While it lives, the vehicle is taken.
            mockMvc.perform(asAdmin(post(ASSIGNMENTS), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body(date, vehicleA, driverA2)))
                    .andExpect(status().isConflict());

            mockMvc.perform(asAdmin(post(ASSIGNMENTS + "/" + first + "/cancel"), COMPANY_A))
                    .andExpect(status().isOk());

            // Cancelled days are excluded from the index, so the truck is free again.
            mockMvc.perform(asAdmin(post(ASSIGNMENTS), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body(date, vehicleA, driverA2)))
                    .andExpect(status().isCreated());
        }
    }

    // --- tenancy ---------------------------------------------------------------------

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("another company cannot read or change this company's day")
        void crossCompanyIsBlocked() throws Exception {
            String date = nextDate();
            String id = create(date, vehicleA, driverA);

            mockMvc.perform(asAdmin(get(ASSIGNMENTS + "/" + id), COMPANY_B)).andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(put(ASSIGNMENTS + "/" + id), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content(body(date, vehicleA, driverA)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(post(ASSIGNMENTS + "/" + id + "/confirm"), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a day cannot schedule another company's shipment")
        void foreignTripIsRefused() throws Exception {
            mockMvc.perform(asAdmin(post(ASSIGNMENTS), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(nextDate(), vehicleA, driverA, tripB)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a viewer reads the day and changes nothing")
        void viewerIsReadOnly() throws Exception {
            String date = nextDate();
            String id = create(date, vehicleA, driverA);

            mockMvc.perform(asViewer(get(ASSIGNMENTS + "/" + id), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(put(ASSIGNMENTS + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(body(date, vehicleA, driverA)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- concurrency -----------------------------------------------------------------

    @Nested
    @DisplayName("two dispatchers at once")
    class Concurrency {

        /**
         * What {@code uq_work_assignment_vehicle_day} is for. Two dispatchers giving one truck a
         * day's work at the same instant both pass any service check - a check and a write are not
         * one operation - and exactly one may end up with a row. The alternative is a truck that is
         * in two people's plans.
         */
        @Test
        @DisplayName("two simultaneous days for one vehicle: exactly one wins")
        void oneVehicleOneDay() throws Exception {
            String date = nextDate();
            int created = race(
                    () -> body(date, vehicleA, driverA),
                    () -> body(date, vehicleA, driverA2));

            assertThat(created).as("one vehicle cannot be in two days' work").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.work_assignment WHERE vehicle_id = '" + vehicleA
                    + "' AND operational_date = '" + date + "' AND status <> 'CANCELLED'")).isEqualTo(1);
        }

        /** The same guarantee for the person, which matters more: a driver cannot be in two places. */
        @Test
        @DisplayName("two simultaneous days for one driver: exactly one wins")
        void oneDriverOneDay() throws Exception {
            String date = nextDate();
            int created = race(
                    () -> body(date, vehicleA, driverA),
                    () -> body(date, vehicleA2, driverA));

            assertThat(created).as("one driver cannot be in two days' work").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.work_assignment WHERE driver_id = '" + driverA
                    + "' AND operational_date = '" + date + "' AND status <> 'CANCELLED'")).isEqualTo(1);
        }

        private int race(java.util.function.Supplier<String> first,
                java.util.function.Supplier<String> second) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<Boolean> a = pool.submit(() -> attempt(first.get(), ready, go));
                Future<Boolean> b = pool.submit(() -> attempt(second.get(), ready, go));
                ready.await();
                go.countDown();
                return (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
            } finally {
                pool.shutdownNow();
            }
        }

        private boolean attempt(String payload, CountDownLatch ready, CountDownLatch go) {
            try {
                ready.countDown();
                go.await();
                return mockMvc.perform(asAdmin(post(ASSIGNMENTS), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON).content(payload))
                        .andReturn().getResponse().getStatus() == 201;
            } catch (Exception refused) {
                // A conflict, a lock timeout or a unique violation are all refusals, which is the
                // outcome under test for the losing thread.
                return false;
            }
        }
    }

    // --- plumbing --------------------------------------------------------------------

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the work assignment fixture", failed);
        }
    }

    private static long count(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the work assignment fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the work assignment fixture", failed);
        }
    }
}
