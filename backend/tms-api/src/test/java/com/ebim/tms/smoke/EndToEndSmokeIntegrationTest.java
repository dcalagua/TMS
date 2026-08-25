package com.ebim.tms.smoke;

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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * The Step 13 API smoke flow, executed end to end through the HTTP layer.
 *
 * <p>Every other integration test in this repository seeds its fixture with SQL and then
 * exercises one module. This one does the opposite: apart from the organization, the two
 * companies and their two users - which are provisioned by an operator, not by TMS - <em>every
 * row it touches is created by calling the API</em>, in the order a planner would. It is the
 * evidence that the eleven steps of the brief compose, not just that each one works in
 * isolation, and the only place where masters, orders and planning are exercised in one
 * transaction chain.
 *
 * <p>It deliberately replaces a "start the server and curl it" smoke run. A live run would
 * need a reachable JWKS endpoint to verify tokens against, and the only real one is the remote
 * Supabase project this repository must never contact. Here the tokens are genuinely signed by
 * a keypair generated in this JVM and genuinely verified by the production filter chain,
 * converter and claim validators ({@link TestJwts}), against a disposable PostGIS container.
 * Nothing is stubbed except the source of the signing key.
 *
 * <p>The steps are ordered and share state on purpose: step N consumes what step N-1 created,
 * exactly as the brief describes it. This is the one class in the suite where test
 * independence is knowingly traded for a flow, so it stays a flow and never grows into a place
 * to put module tests.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EndToEndSmokeIntegrationTest.JwtDecoderOverride.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Step 13 smoke: authenticate -> masters -> fleet -> order -> plan -> confirm")
class EndToEndSmokeIntegrationTest {

    private static final String MASTERDATA = "/api/v1/masterdata";
    private static final String FLEET = "/api/v1/fleet";
    private static final String ORDERS = "/api/v1/orders";
    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("77777777-0000-4000-8000-0000000000c2");
    private static final UUID PLANNER_A_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");
    private static final UUID PLANNER_B_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e2");

    /** The whole flow runs against one service date so every step lines up with the plan. */
    private static final LocalDate SERVICE_DATE = LocalDate.now().plusDays(1);

    /**
     * 08:00 on the planning day in the smoke company's own time zone. It has to be derived
     * from {@link #SERVICE_DATE} rather than fixed: {@code ShipmentTimeRules} refuses a
     * departure that falls on a day the run is not planning, judged in
     * {@code tms.company.time_zone}.
     */
    private static final String DEPARTURE = SERVICE_DATE.atTime(8, 0)
            .atZone(java.time.ZoneId.of("America/Lima")).toOffsetDateTime().toString();

    private static String jdbcUrl;

    // Carried between the ordered steps: what each step created, the next one consumes.
    private static String zoneId;
    private static String originId;
    private static String destinationId;
    private static String frequencyId;
    private static String routeId;
    private static String carrierId;
    private static String vehicleTypeId;
    private static String vehicleId;
    private static String orderId;
    private static String runId;
    private static String tripId;

    @Autowired
    private MockMvc mockMvc;

    private String plannerA;
    private String plannerB;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_smoke");
        seedTenants();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    /**
     * The only SQL in this class. Organizations, companies, users and memberships are
     * provisioned out of band - TMS has no endpoint that creates a tenant, by design - so
     * there is no API call that could replace this.
     */
    private static void seedTenants() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'SMOKE-ORG', 'Smoke Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'SMOKE-A', 'Smoke Company A', 'America/Lima'),
                    ('%s', '%s', 'SMOKE-B', 'Smoke Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'smoke.a@example.invalid', 'Smoke Planner A'),
                    ('%s', 'smoke.b@example.invalid', 'Smoke Planner B');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION,
                PLANNER_A_AUTH, PLANNER_B_AUTH));

        membership("smoke.a@example.invalid", COMPANY_A);
        membership("smoke.b@example.invalid", COMPANY_B);
    }

    private static void membership(String email, UUID companyId) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = '%s';

                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id
                FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%s'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, companyId, email, email, companyId));
    }

    @BeforeEach
    void mintTokens() {
        plannerA = TestJwts.validFor(PLANNER_A_AUTH);
        plannerB = TestJwts.validFor(PLANNER_B_AUTH);
    }

    // --- 1. authenticate ----------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("1. a signed token authenticates and resolves to an app user with one company")
    void authenticate() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + plannerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("smoke.a@example.invalid"))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andExpect(jsonPath("$.companies[0].id").value(COMPANY_A.toString()));

        // The same endpoint without a token is the control: authentication is real.
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    // --- 2. select the authorized company -----------------------------------------

    @Test
    @Order(2)
    @DisplayName("2. selecting the authorized company succeeds; selecting the other one is refused")
    void selectCompany() throws Exception {
        mockMvc.perform(asPlannerA(get("/api/v1/companies/current"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SMOKE-A"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("planning.plan:manage")));

        mockMvc.perform(asPlannerA(get("/api/v1/companies/current"), COMPANY_B))
                .andExpect(status().isForbidden());
    }

    // --- 3. Zone / Locations / Frequency / Route ----------------------------------

    @Test
    @Order(3)
    @DisplayName("3. Zone, Locations, Frequency and Route are created through the API")
    void createMasterdata() throws Exception {
        created(mockMvc.perform(asPlannerA(post(MASTERDATA + "/zones"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-ZONE","name":"Smoke Zone","description":"Lima metropolitan area"}
                                """)), id -> zoneId = id)
                .andExpect(jsonPath("$.code").value("SMK-ZONE"));

        // Two physical places, one master. The warehouse only ships; the customer site only
        // receives - and each says so with an operational role, not with a second record.
        created(mockMvc.perform(asPlannerA(post(MASTERDATA + "/locations"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-ORIG","name":"Smoke Origin","type":"WAREHOUSE",
                                 "roles":["ORIGIN"],
                                 "address":"Av. Industrial 100","latitude":-12.0464,"longitude":-77.0428,
                                 "country":"PE","timeZone":"America/Lima","serviceTimeMinutes":0}
                                """)), id -> originId = id)
                .andExpect(jsonPath("$.type").value("WAREHOUSE"))
                .andExpect(jsonPath("$.roles").value(java.util.List.of("ORIGIN")));

        created(mockMvc.perform(asPlannerA(post(MASTERDATA + "/locations"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-DEST","name":"Smoke Destination","type":"CUSTOMER",
                                 "roles":["DESTINATION"],
                                 "address":"Av. Comercio 500","district":"Surco","province":"Lima",
                                 "department":"Lima","country":"PE","latitude":-12.1200,"longitude":-76.9900,
                                 "timeZone":"America/Lima","zoneId":"%s","serviceTimeMinutes":30}
                                """.formatted(zoneId))), id -> destinationId = id)
                .andExpect(jsonPath("$.zoneId").value(zoneId));

        created(mockMvc.perform(asPlannerA(post(MASTERDATA + "/frequencies"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-FREQ","name":"Weekdays","description":"Monday to Friday",
                                 "weeklyRules":[
                                   {"dayOfWeek":1,"enabled":true,"cutoffTime":"16:00:00","leadTimeDays":1},
                                   {"dayOfWeek":2,"enabled":true,"cutoffTime":"16:00:00","leadTimeDays":1},
                                   {"dayOfWeek":3,"enabled":true,"cutoffTime":"16:00:00","leadTimeDays":1},
                                   {"dayOfWeek":4,"enabled":true,"cutoffTime":"16:00:00","leadTimeDays":1},
                                   {"dayOfWeek":5,"enabled":true,"cutoffTime":"16:00:00","leadTimeDays":1},
                                   {"dayOfWeek":6,"enabled":false},
                                   {"dayOfWeek":7,"enabled":false}]}
                                """)), id -> frequencyId = id);

        created(mockMvc.perform(asPlannerA(post(MASTERDATA + "/routes"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-ROUTE","name":"Smoke Route","originId":"%s","zoneId":"%s",
                                 "frequencyId":"%s","referenceDistanceKm":42.5,"referenceDurationMinutes":75,
                                 "stops":[{"destinationId":"%s"}]}
                                """.formatted(originId, zoneId, frequencyId, destinationId))), id -> routeId = id)
                .andExpect(jsonPath("$.originId").value(originId))
                .andExpect(jsonPath("$.stops.length()").value(1));

        assertThat(zoneId).isNotBlank();
        assertThat(routeId).isNotBlank();
    }

    // --- 4. Carrier / Vehicle Type / Vehicle --------------------------------------

    @Test
    @Order(4)
    @DisplayName("4. Carrier, Vehicle Type and Vehicle are created through the API")
    void createFleet() throws Exception {
        created(mockMvc.perform(asPlannerA(post(FLEET + "/carriers"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-CARR","businessName":"Smoke Transport S.A.C.",
                                 "taxIdType":"RUC","taxIdValue":"20100000001",
                                 "contactName":"Operations desk","phone":"+51 1 555 0100",
                                 "email":"ops@smoke.invalid"}
                                """)), id -> carrierId = id);

        created(mockMvc.perform(asPlannerA(post(FLEET + "/vehicle-types"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-TYPE","name":"Rigid 12t","maxWeightKg":12000,"maxVolumeM3":45,
                                 "maxPallets":16,"lengthM":7.2,"widthM":2.4,"heightM":2.6,
                                 "bodyType":"DRY_VAN","temperatureControlled":false,"axles":2}
                                """)), id -> vehicleTypeId = id)
                .andExpect(jsonPath("$.maxWeightKg").value(12000.000));

        created(mockMvc.perform(asPlannerA(post(FLEET + "/vehicles"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SMK-VEH","licensePlate":"SMK-001","carrierId":"%s",
                                 "vehicleTypeId":"%s","availabilityStatus":"AVAILABLE"}
                                """.formatted(carrierId, vehicleTypeId))), id -> vehicleId = id)
                .andExpect(jsonPath("$.vehicleTypeId").value(vehicleTypeId))
                .andExpect(jsonPath("$.carrierId").value(carrierId));
    }

    // --- 5. Order + line, then mark ready ------------------------------------------

    @Test
    @Order(5)
    @DisplayName("5. an order with one line is created, its totals are computed, and it is marked ready")
    void createOrderAndMarkReady() throws Exception {
        created(mockMvc.perform(asPlannerA(post(ORDERS), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originId":"%s","destinationId":"%s","customerName":"Smoke Customer",
                                 "customerReference":"PO-4711","serviceDate":"%s","priority":"NORMAL",
                                 "requestedWindowStart":"09:00:00","requestedWindowEnd":"13:00:00",
                                 "lines":[{"materialCode":"MAT-001","materialDescription":"Palletised goods",
                                           "quantity":10,"uom":"PAL","unitWeightKg":500,"unitVolumeM3":1.8,
                                           "palletQuantity":10}]}
                                """.formatted(originId, destinationId, SERVICE_DATE))), id -> orderId = id)
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                // Weight and volume are server-computed as quantity x unit value; pallets are
                // the line's own declared contribution, never derived from quantity (V10).
                .andExpect(jsonPath("$.totalWeightKg").value(5000.000))
                .andExpect(jsonPath("$.totalVolumeM3").value(18.0000))
                .andExpect(jsonPath("$.totalPallets").value(10.00))
                .andExpect(jsonPath("$.lines[0].lineWeightKg").value(5000.000))
                .andExpect(jsonPath("$.lines[0].lineVolumeM3").value(18.0000));

        mockMvc.perform(asPlannerA(post(ORDERS + "/" + orderId + "/mark-ready"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));

        // It is now in the planning pool, which is what step 9 will consume.
        mockMvc.perform(asPlannerA(get(PLANNING + "/eligible-orders"), COMPANY_A)
                        .param("originId", originId)
                        .param("serviceDate", SERVICE_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));
    }

    // --- 6. Planning Run ------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("6. a planning run opens for that origin and date")
    void createPlanningRun() throws Exception {
        String response = mockMvc.perform(asPlannerA(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originId":"%s","planningDate":"%s","notes":"Step 13 smoke run"}
                                """.formatted(originId, SERVICE_DATE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.status").value("DRAFT"))
                .andExpect(jsonPath("$.run.originId").value(originId))
                .andExpect(jsonPath("$.trips.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        runId = JsonPath.read(response, "$.run.id");
    }

    // --- 7. Trip --------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("7. a trip is created inside the run, initially with no vehicle")
    void createTrip() throws Exception {
        String response = mockMvc.perform(asPlannerA(post(PLANNING + "/runs/" + runId + "/trips"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trip.status").value("DRAFT"))
                .andExpect(jsonPath("$.trip.tripNumber").value(1))
                .andExpect(jsonPath("$.trip.vehicleId").doesNotExist())
                // With no vehicle there is no limit to check yet, so capacity is unlimited.
                .andExpect(jsonPath("$.trip.capacity.weight.unlimited").value(true))
                .andReturn().getResponse().getContentAsString();
        tripId = JsonPath.read(response, "$.trip.id");
    }

    // --- 8. assign the vehicle ------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("8. the vehicle is assigned to the trip and its type's limits become the trip's limits")
    void assignVehicle() throws Exception {
        mockMvc.perform(asPlannerA(put(TRIPS + "/" + tripId + "/vehicle"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","plannedDepartureAt":"%s","version":%d}
                                """.formatted(vehicleId, DEPARTURE, tripVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.vehicleId").value(vehicleId))
                .andExpect(jsonPath("$.trip.carrierId").value(carrierId))
                .andExpect(jsonPath("$.trip.capacity.source").value("LIVE"))
                .andExpect(jsonPath("$.trip.capacity.weight.limit").value(12000.000))
                .andExpect(jsonPath("$.trip.capacity.weight.unlimited").value(false));
    }

    // --- 9. assign the order --------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("9. the order is assigned to the trip, becomes PLANNED and leaves the eligible pool")
    void assignOrder() throws Exception {
        mockMvc.perform(asPlannerA(post(TRIPS + "/" + tripId + "/assignments"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].orderId").value(orderId))
                // Assigning an order to a trip creates the stop for its destination.
                .andExpect(jsonPath("$.stops.length()").value(1))
                .andExpect(jsonPath("$.stops[0].destinationId").value(destinationId));

        mockMvc.perform(asPlannerA(get(ORDERS + "/" + orderId), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));

        mockMvc.perform(asPlannerA(get(PLANNING + "/eligible-orders"), COMPANY_A)
                        .param("originId", originId)
                        .param("serviceDate", SERVICE_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // The link is an assignment row, not a trip_id column on the order (V11's explicit
        // decision). Asserted here so a future "shortcut" refactor fails this smoke run.
        assertThat(queryLong("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '"
                + orderId + "' AND trip_id = '" + tripId + "' AND status = 'ACTIVE'")).isEqualTo(1);
    }

    // --- 10. verify capacity --------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("10. capacity is computed server-side from the assigned load against the vehicle type")
    void verifyCapacity() throws Exception {
        mockMvc.perform(asPlannerA(get(TRIPS + "/" + tripId + "/capacity"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId))
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.weight.used").value(5000.000))
                .andExpect(jsonPath("$.weight.limit").value(12000.000))
                .andExpect(jsonPath("$.weight.remaining").value(7000.000))
                .andExpect(jsonPath("$.volume.used").value(18.0000))
                .andExpect(jsonPath("$.volume.limit").value(45.0000))
                .andExpect(jsonPath("$.pallets.used").value(10.00))
                .andExpect(jsonPath("$.pallets.limit").value(16.00))
                .andExpect(jsonPath("$.withinCapacity").value(true))
                .andExpect(jsonPath("$.weight.exceeded").value(false));
    }

    // --- 11. confirm the planning run -----------------------------------------------

    @Test
    @Order(11)
    @DisplayName("11. confirming freezes the plan: run and trip CONFIRMED, capacity becomes a SNAPSHOT")
    void confirmPlanningRun() throws Exception {
        mockMvc.perform(asPlannerA(post(PLANNING + "/runs/" + runId + "/confirm"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + runVersion() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.trips.length()").value(1))
                .andExpect(jsonPath("$.trips[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.trips[0].capacity.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.trips[0].capacity.weight.limit").value(12000.000));

        // A confirmed plan is closed: the same call cannot be replayed.
        mockMvc.perform(asPlannerA(post(PLANNING + "/runs/" + runId + "/confirm"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + runVersion() + "}"))
                .andExpect(status().isConflict());
    }

    // --- isolation smoke -------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("isolation: company B's planner cannot read any of company A's records")
    void anotherCompanyCannotRead() throws Exception {
        // Presenting company A's id is refused at the scope filter: B has no membership there.
        mockMvc.perform(asPlannerB(get(ORDERS + "/" + orderId), COMPANY_A)).andExpect(status().isForbidden());
        mockMvc.perform(asPlannerB(get(TRIPS + "/" + tripId), COMPANY_A)).andExpect(status().isForbidden());

        // Presenting B's own company id is authorized, so the request reaches the service - and
        // there every record of company A is simply not found. This is the case that matters:
        // an authenticated, correctly scoped caller still cannot reach across the tenant line.
        mockMvc.perform(asPlannerB(get(ORDERS + "/" + orderId), COMPANY_B)).andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(TRIPS + "/" + tripId), COMPANY_B)).andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(TRIPS + "/" + tripId + "/capacity"), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(PLANNING + "/runs/" + runId), COMPANY_B)).andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(MASTERDATA + "/locations/" + originId), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(MASTERDATA + "/locations/" + destinationId), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(MASTERDATA + "/routes/" + routeId), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(FLEET + "/vehicles/" + vehicleId), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(get(FLEET + "/carriers/" + carrierId), COMPANY_B))
                .andExpect(status().isNotFound());

        // A list is scoped too, so nothing leaks through the collection endpoints either.
        mockMvc.perform(asPlannerB(get(ORDERS), COMPANY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(asPlannerB(get(MASTERDATA + "/locations"), COMPANY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(13)
    @DisplayName("isolation: company B's planner cannot mutate any of company A's records")
    void anotherCompanyCannotMutate() throws Exception {
        String before = queryString("SELECT status FROM tms.transport_order WHERE id = '" + orderId + "'");

        mockMvc.perform(asPlannerB(post(ORDERS + "/" + orderId + "/cancel"), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(put(ORDERS + "/" + orderId), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originId":"%s","destinationId":"%s","serviceDate":"%s","priority":"URGENT",
                                 "lines":[{"materialCode":"X","materialDescription":"X","quantity":1,"uom":"EA"}]}
                                """.formatted(originId, destinationId, SERVICE_DATE)))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(delete(TRIPS + "/" + tripId + "/assignments/" + orderId), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(post(TRIPS + "/" + tripId + "/assignments"), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(post(PLANNING + "/runs/" + runId + "/cancel"), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(post(MASTERDATA + "/locations/" + originId + "/deactivate"), COMPANY_B))
                .andExpect(status().isNotFound());
        mockMvc.perform(asPlannerB(post(FLEET + "/vehicles/" + vehicleId + "/deactivate"), COMPANY_B))
                .andExpect(status().isNotFound());

        // Nothing moved: the refusals above are refusals, not partially applied writes.
        assertThat(queryString("SELECT status FROM tms.transport_order WHERE id = '" + orderId + "'"))
                .isEqualTo(before);
        assertThat(queryString("SELECT status FROM tms.trip WHERE id = '" + tripId + "'")).isEqualTo("CONFIRMED");
        assertThat(queryString("SELECT status FROM tms.planning_run WHERE id = '" + runId + "'"))
                .isEqualTo("CONFIRMED");
        assertThat(queryLong("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + orderId
                + "' AND status = 'ACTIVE'")).isEqualTo(1);
    }

    // --- helpers ----------------------------------------------------------------------

    private long tripVersion() throws Exception {
        Number version = JsonPath.read(mockMvc.perform(asPlannerA(get(TRIPS + "/" + tripId), COMPANY_A))
                .andReturn().getResponse().getContentAsString(), "$.trip.version");
        return version.longValue();
    }

    private long runVersion() throws Exception {
        Number version = JsonPath.read(mockMvc.perform(asPlannerA(get(PLANNING + "/runs/" + runId), COMPANY_A))
                .andReturn().getResponse().getContentAsString(), "$.run.version");
        return version.longValue();
    }

    /**
     * Asserts a 201 and hands the new resource's id to {@code sink} <em>before</em> the caller
     * chains any body assertion, then returns the result so it can. Capturing first is what
     * keeps a failing expectation local: otherwise the id is never assigned and every
     * subsequent step of the flow fails on a null instead of reporting the real cause.
     */
    private static ResultActions created(ResultActions actions, Consumer<String> sink) throws Exception {
        actions.andExpect(status().isCreated());
        sink.accept(JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id"));
        return actions;
    }

    private MockHttpServletRequestBuilder asPlannerA(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + plannerA)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asPlannerB(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + plannerB)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String queryString(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the smoke fixture", failed);
        }
    }

    private static long queryLong(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the smoke fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the smoke tenants", failed);
        }
    }
}
