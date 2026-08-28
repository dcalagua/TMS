package com.ebim.tms.costing.api;

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
 * Own-fleet cost profiles end to end (migration V48, debt D6).
 *
 * <p>What no unit test can show: that the API runs, that <b>another company can neither read our
 * rates nor cost our trucks</b>, that the overlap rule is enforced by the database rather than by
 * hope, and that a viewer without {@code costing.own_fleet:write} cannot set what a truck costs.
 * The arithmetic and the precedence are proven without a database in
 * {@code OwnFleetCostCalculatorTest} and {@code OwnFleetProfileResolverTest}.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OwnFleetCostingApiIntegrationTest.JwtDecoderOverride.class)
class OwnFleetCostingApiIntegrationTest {

    private static final String PROFILES = "/api/v1/costing/own-fleet/profiles";

    private static final UUID ORGANIZATION = UUID.fromString("88888888-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("88888888-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("88888888-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e1");
    private static final UUID PLANNER_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e2");
    private static final UUID VIEWER_AUTH = UUID.fromString("88888888-0000-4000-8000-0000000000e3");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String vehicleA;
    private static String vehicleTypeA;
    private static String vehicleB;
    private static String ownTripA;
    private static String carrierTripA;
    private static String unassignedTripA;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String plannerToken;
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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_own_fleet_costing_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'OFC-ORG', 'Own Fleet Costing Org')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'OFC-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'OFC-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'ofc.admin@example.invalid', 'OFC Admin'), ('" + PLANNER_AUTH
                + "', 'ofc.planner@example.invalid', 'OFC Planner'), ('" + VIEWER_AUTH
                + "', 'ofc.viewer@example.invalid', 'OFC Viewer')");
        membership("ofc.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("ofc.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("ofc.planner@example.invalid", COMPANY_A, "PLANNER");
        membership("ofc.viewer@example.invalid", COMPANY_A, "VIEWER");

        vehicleTypeA = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY_A + "', 'VT-A', 'Rigid', 10000, 40, 20)"
                + " RETURNING id");
        vehicleA = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                + " VALUES ('" + COMPANY_A + "', '" + vehicleTypeA + "', 'OFC-VEH-A', 'PLT-A') RETURNING id");

        String typeB = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY_B + "', 'VT-B', 'Rigid', 10000, 40, 20)"
                + " RETURNING id");
        vehicleB = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                + " VALUES ('" + COMPANY_B + "', '" + typeB + "', 'OFC-VEH-B', 'PLT-B') RETURNING id");

        String carrier = idOf("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY_A + "', 'OFC-CARR', 'Transportes Lima', 'RUC',"
                + " '20100000001') RETURNING id");
        String carrierVehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                + " license_plate, carrier_id) VALUES ('" + COMPANY_A + "', '" + vehicleTypeA
                + "', 'OFC-VEH-C', 'PLT-C', '" + carrier + "') RETURNING id");

        ownTripA = trip(COMPANY_A, "OFC-ORIG-1", vehicleA, null);
        carrierTripA = trip(COMPANY_A, "OFC-ORIG-2", carrierVehicle, carrier);
        unassignedTripA = trip(COMPANY_A, "OFC-ORIG-3", null, null);
    }

    private static String trip(UUID companyId, String originCode, String vehicleId, String carrierId) {
        String origin = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId
                + "', '" + originCode + "', 'Origin') RETURNING id");
        String run = idOf("INSERT INTO tms.planning_run (company_id, plan_number, origin_id,"
                + " planning_date, status) VALUES ('" + companyId + "', 'PL-" + originCode + "', '" + origin
                + "', '2026-06-15', 'DRAFT') RETURNING id");
        return idOf("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number,"
                + " vehicle_id, carrier_id, planned_departure_at) VALUES ('" + companyId + "', '" + run
                + "', '2026-06-15', " + SEQUENCE.incrementAndGet() + ", "
                + (vehicleId == null ? "NULL" : "'" + vehicleId + "'") + ", "
                + (carrierId == null ? "NULL" : "'" + carrierId + "'")
                + ", '2026-06-15T08:00:00Z') RETURNING id");
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
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    private static String profileBody(String vehicleId, String vehicleTypeId, String currency,
            String from, String to, String rates) {
        return "{" + (vehicleId == null ? "" : "\"vehicleId\":\"" + vehicleId + "\",")
                + (vehicleTypeId == null ? "" : "\"vehicleTypeId\":\"" + vehicleTypeId + "\",")
                + "\"currency\":\"" + currency + "\",\"effectiveFrom\":\"" + from + "\""
                + (to == null ? "" : ",\"effectiveTo\":\"" + to + "\"") + "," + rates + "}";
    }

    @Nested
    @DisplayName("configuring rates")
    class Configuring {

        @Test
        @DisplayName("a full profile is saved and reads back as ACTIVE")
        void createsAndReads() throws Exception {
            String id = create(profileBody(freshVehicle("OFC-CR"), null, "PEN", "2026-01-01", "2027-01-01",
                    "\"fixedTripAmount\":100.00,\"fuelPerKm\":0.65,\"driverPerHour\":18.00"));

            mockMvc.perform(asAdmin(get(PROFILES + "/" + id), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currency").value("PEN"))
                    .andExpect(jsonPath("$.state").value("ACTIVE"))
                    .andExpect(jsonPath("$.needsDistance").value(true))
                    .andExpect(jsonPath("$.needsDuty").value(true))
                    // Not configured, and it must come back null rather than 0.00 - the whole
                    // "empty is not zero" rule, visible at the API boundary.
                    .andExpect(jsonPath("$.depreciationPerKm").doesNotExist());
        }

        @Test
        @DisplayName("two profiles overlapping on one vehicle are refused by the database")
        void overlapRefused() throws Exception {
            String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                    + " license_plate) VALUES ('" + COMPANY_A + "', '" + vehicleTypeA + "', 'OFC-OVL',"
                    + " 'PLT-OVL') RETURNING id");
            create(profileBody(vehicle, null, "PEN", "2026-01-01", null, "\"fixedTripAmount\":100.00"));

            mockMvc.perform(asAdmin(post(PROFILES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(profileBody(vehicle, null, "PEN", "2026-06-01", null,
                                    "\"fixedTripAmount\":120.00")))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a profile naming both a vehicle and a type is refused with a readable message")
        void bothTargetsRefused() throws Exception {
            mockMvc.perform(asAdmin(post(PROFILES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(profileBody(vehicleA, vehicleTypeA, "PEN", "2030-01-01", null,
                                    "\"fixedTripAmount\":100.00")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a planner reads rates and cannot set them")
        void plannerReadsAndCannotWrite() throws Exception {
            // Choosing between a carrier and our own truck is the planner's decision, so they see
            // the cost. Deciding what the truck costs to run is a finance decision about the
            // business, so they do not set it.
            mockMvc.perform(asPlanner(get(PROFILES), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asPlanner(post(PROFILES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(profileBody(freshVehicle("OFC-PL"), null, "PEN", "2031-01-01", null,
                                    "\"fixedTripAmount\":1.00")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a viewer cannot read our cost structure at all")
        void viewerCannotRead() throws Exception {
            // Not an oversight. These rates are what we pay a driver by the hour and what we
            // believe fuel runs at - our cost structure, not our operation - and a general viewer
            // has no business in them. The same line rates draws for tariffs.
            mockMvc.perform(asViewer(get(PROFILES), COMPANY_A)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deactivating a profile stops it costing anything")
        void deactivate() throws Exception {
            String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                    + " license_plate) VALUES ('" + COMPANY_A + "', '" + vehicleTypeA + "', 'OFC-DEA',"
                    + " 'PLT-DEA') RETURNING id");
            String id = create(profileBody(vehicle, null, "PEN", "2026-01-01", null,
                    "\"fixedTripAmount\":100.00"));

            mockMvc.perform(asAdmin(put(PROFILES + "/" + id + "/active"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("INACTIVE"));
        }
    }

    @Nested
    @DisplayName("costing a trip")
    class Costing {

        @Test
        @DisplayName("a trip on our own truck is costed, and the total is labelled an internal cost")
        void costsOwnFleet() throws Exception {
            create(profileBody(vehicleA, null, "PEN", "2026-01-01", "2027-01-01",
                    "\"fixedTripAmount\":100.00,\"tollAmount\":30.00"));

            mockMvc.perform(asAdmin(get(quoteUrl(ownTripA)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nature").value("OWN_FLEET_INTERNAL_COST"))
                    .andExpect(jsonPath("$.complete").value(true))
                    .andExpect(jsonPath("$.comparableTotal").value(130.00))
                    .andExpect(jsonPath("$.profileScope").value("VEHICLE"))
                    .andExpect(jsonPath("$.currency").value("PEN"));
        }

        @Test
        @DisplayName("a truck nobody has configured has NO cost, not a cost of zero")
        void unconfiguredIsNotFree() throws Exception {
            String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                    + " license_plate) VALUES ('" + COMPANY_A + "', '" + vehicleTypeA + "', 'OFC-NOPROF',"
                    + " 'PLT-NP') RETURNING id");
            String trip = trip(COMPANY_A, "OFC-ORIG-NP", vehicle, null);

            mockMvc.perform(asAdmin(get(quoteUrl(trip)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unavailableReason").value("NO_PROFILE_IN_FORCE"))
                    // The assertion this whole job exists for: no total, and no zero either. A zero
                    // here would make every unconfigured truck the cheapest option on every screen.
                    .andExpect(jsonPath("$.comparableTotal").doesNotExist())
                    .andExpect(jsonPath("$.complete").value(false));
        }

        @Test
        @DisplayName("a profile charging per kilometre withholds the total when the route cannot be measured")
        void unmeasurableDistanceWithholdsTheTotal() throws Exception {
            String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                    + " license_plate) VALUES ('" + COMPANY_A + "', '" + vehicleTypeA + "', 'OFC-NOKM',"
                    + " 'PLT-NK') RETURNING id");
            String trip = trip(COMPANY_A, "OFC-ORIG-NK", vehicle, null);
            create(profileBody(vehicle, null, "PEN", "2026-01-01", null,
                    "\"fixedTripAmount\":100.00,\"fuelPerKm\":0.65"));

            // The trip has no stops with coordinates, so no distance can be measured.
            mockMvc.perform(asAdmin(get(quoteUrl(trip)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.complete").value(false))
                    .andExpect(jsonPath("$.comparableTotal").doesNotExist())
                    .andExpect(jsonPath("$.blockingReasons[0]").value("DISTANCE_UNKNOWN"))
                    // The breakdown survives for whoever has to fix it, and says what it would cost.
                    .andExpect(jsonPath("$.partialSubtotal").value(100.00));
        }

        @Test
        @DisplayName("a subcontracted trip has a carrier's price, not an internal cost")
        void carrierTripIsNotCosted() throws Exception {
            mockMvc.perform(asAdmin(get(quoteUrl(carrierTripA)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unavailableReason").value("NOT_OWN_FLEET"))
                    .andExpect(jsonPath("$.comparableTotal").doesNotExist());
        }

        @Test
        @DisplayName("a trip with no vehicle says so rather than costing nothing")
        void unassignedTrip() throws Exception {
            mockMvc.perform(asAdmin(get(quoteUrl(unassignedTripA)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unavailableReason").value("NO_VEHICLE_ASSIGNED"));
        }

        @Test
        @DisplayName("a vehicle-type profile costs a truck that has none of its own")
        void fallsBackToType() throws Exception {
            String type = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                    + " max_volume_m3, max_pallets) VALUES ('" + COMPANY_A + "', 'VT-FB', 'Van', 3000, 12, 6)"
                    + " RETURNING id");
            String vehicle = idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code,"
                    + " license_plate) VALUES ('" + COMPANY_A + "', '" + type + "', 'OFC-FB', 'PLT-FB')"
                    + " RETURNING id");
            String trip = trip(COMPANY_A, "OFC-ORIG-FB", vehicle, null);
            create(profileBody(null, type, "PEN", "2026-01-01", null, "\"fixedTripAmount\":77.00"));

            mockMvc.perform(asAdmin(get(quoteUrl(trip)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.comparableTotal").value(77.00))
                    .andExpect(jsonPath("$.profileScope").value("VEHICLE_TYPE"));
        }
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("company B cannot read company A's rates")
        void cannotReadAcrossCompanies() throws Exception {
            String id = create(profileBody(freshVehicle("OFC-XR"), null, "PEN", "2028-01-01", null,
                    "\"fixedTripAmount\":100.00"));

            mockMvc.perform(asAdmin(get(PROFILES + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("company A cannot configure a cost for company B's vehicle")
        void cannotConfigureAcrossCompanies() throws Exception {
            mockMvc.perform(asAdmin(post(PROFILES), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(profileBody(vehicleB, null, "PEN", "2029-01-01", null,
                                    "\"fixedTripAmount\":100.00")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("company B cannot cost company A's trip")
        void cannotQuoteAcrossCompanies() throws Exception {
            mockMvc.perform(asAdmin(get(quoteUrl(ownTripA)), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("company B's list does not contain company A's profiles")
        void listIsScoped() throws Exception {
            String vehicle = freshVehicle("OFC-LS");
            create(profileBody(vehicle, null, "PEN", "2032-01-01", null, "\"fixedTripAmount\":9.00"));

            mockMvc.perform(asAdmin(get(PROFILES), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.vehicleId == '" + vehicle + "')]").isEmpty());
        }
    }

    /**
     * A vehicle nobody else uses.
     *
     * <p>{@code ex_own_fleet_profile_vehicle_no_overlap} is a fact about a vehicle across all time,
     * so two tests configuring the same truck for different years still collide the moment one of
     * their windows is open-ended. Each test that saves a profile gets its own truck instead - the
     * same reason the work-assignment test gives each test its own date.
     */
    private static String freshVehicle(String code) {
        return idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                + " VALUES ('" + COMPANY_A + "', '" + vehicleTypeA + "', '" + code + "', 'PLT-" + code
                + "') RETURNING id");
    }

    private static String quoteUrl(String tripId) {
        return "/api/v1/costing/own-fleet/trips/" + tripId + "/quote";
    }

    private String create(String body) throws Exception {
        String response = mockMvc.perform(asAdmin(post(PROFILES), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asPlanner(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + plannerToken)
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
            throw new IllegalStateException("could not seed the own-fleet costing fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the own-fleet costing fixture", failed);
        }
    }
}
