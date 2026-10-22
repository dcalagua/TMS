package com.ebim.tms.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * The carrier scorecard against a real PostgreSQL (ITEM 3, {@code docs/domain/CARRIER_SCORECARD_V1.md}).
 *
 * <p>The fixture is small enough to count by hand, and every assertion is that hand count: two trips
 * of TRLIT (one left on time, one an hour late), two stops (one in its window, one out), two
 * deliveries (one full, one partial), one exception, two documents (both delivered, one returned),
 * an accepted offer; TRAND refused one load and let another lapse; one trip of our own fleet that has
 * not left; and one trip that belongs to nobody. A second company holds a trip of its own that must
 * not appear anywhere.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CarrierScorecardApiIntegrationTest.JwtDecoderOverride.class)
class CarrierScorecardApiIntegrationTest {

    private static final String SCORECARD = "/api/v1/reporting/carrier-scorecard";
    private static final String INVOICES = "/api/v1/settlement/invoices";
    private static final String RANGE = "?from=2026-10-10&to=2026-10-20";
    private static final String DAY = "2026-10-15";

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000a01");
    private static final UUID COMPANY_A = UUID.fromString("99999999-0000-4000-8000-000000000ac1");
    private static final UUID COMPANY_B = UUID.fromString("99999999-0000-4000-8000-000000000ac2");
    private static final UUID ADMIN_AUTH = UUID.fromString("99999999-0000-4000-8000-000000000ae1");
    private static final UUID VIEWER_AUTH = UUID.fromString("99999999-0000-4000-8000-000000000ae2");
    private static final UUID MONITOR_AUTH = UUID.fromString("99999999-0000-4000-8000-000000000ae3");
    private static final UUID FLEET_AUTH = UUID.fromString("99999999-0000-4000-8000-000000000ae4");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** A filter path ({@code $.carriers[?(...)].x}) answers a list: one carrier whose field is null. */
    private static final org.hamcrest.Matcher<Iterable<?>> NULL_IN_FILTER =
            org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue());

    private static String jdbcUrl;
    private static String adminUserId;
    private static String trlit;
    private static String trand;
    private static String tripOnTime;
    private static String tripLate;
    private static String rateCard;

    @Autowired
    private MockMvc mockMvc;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_carrier_scorecard_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'SC-ORG', 'Scorecard Organization')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'SC-A', 'Company A', 'America/Lima'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'SC-B', 'Company B', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'sc.admin@example.invalid', 'SC Admin'), ('" + VIEWER_AUTH
                + "', 'sc.viewer@example.invalid', 'SC Viewer'), ('" + MONITOR_AUTH
                + "', 'sc.monitor@example.invalid', 'SC Monitor'), ('" + FLEET_AUTH
                + "', 'sc.fleet@example.invalid', 'SC Fleet')");
        adminUserId = scalar("SELECT id FROM tms.app_user WHERE email = 'sc.admin@example.invalid'");
        // Two installation-specific roles, so the two edges are tested by permission and not by the
        // accident of what a seeded role holds: one that sees transport and nothing finer, and one
        // that sees no transport at all.
        execute("INSERT INTO tms.role (code, name, scope_level, system_managed) VALUES"
                + " ('SC_MONITOR_ONLY', 'Monitor only', 'COMPANY', false),"
                + " ('SC_FLEET_ONLY', 'Fleet only', 'COMPANY', false)");
        execute("INSERT INTO tms.role_permission (role_id, permission_id) SELECT r.id, p.id FROM tms.role r"
                + " CROSS JOIN tms.permission p WHERE r.code = 'SC_MONITOR_ONLY' AND p.code = 'monitoring.transport:read'");
        execute("INSERT INTO tms.role_permission (role_id, permission_id) SELECT r.id, p.id FROM tms.role r"
                + " CROSS JOIN tms.permission p WHERE r.code = 'SC_FLEET_ONLY' AND p.code = 'fleet.carrier:read'");
        membership("sc.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("sc.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("sc.viewer@example.invalid", COMPANY_A, "VIEWER");
        membership("sc.monitor@example.invalid", COMPANY_A, "SC_MONITOR_ONLY");
        membership("sc.fleet@example.invalid", COMPANY_A, "SC_FLEET_ONLY");

        trlit = carrier(COMPANY_A, "TRLIT", "Transportes Litoral");
        trand = carrier(COMPANY_A, "TRAND", "Transandina");
        String trlitVehicle = vehicle(COMPANY_A, trlit);
        String trandVehicle = vehicle(COMPANY_A, trand);
        String ownVehicle = vehicle(COMPANY_A, null);

        // TRLIT: one on time, one an hour late.
        tripOnTime = departedTrip(COMPANY_A, trlit, trlitVehicle, DAY + "T13:00:00Z", DAY + "T13:00:00Z");
        // A second truck: a vehicle runs one live trip per planning day (uq_trip_vehicle_active_planning_date).
        tripLate = departedTrip(COMPANY_A, trlit, vehicle(COMPANY_A, trlit), DAY + "T13:00:00Z", DAY + "T14:00:00Z");
        // The own fleet: confirmed, not left - its punctuality is unmeasured, not 0%.
        confirmedTrip(COMPANY_A, null, ownVehicle, DAY + "T13:00:00Z");
        // Nobody's: a draft with neither carrier nor vehicle.
        draftTrip(COMPANY_A);
        // A trip of TRAND outside the range: must not be counted.
        departedTrip(COMPANY_A, trand, trandVehicle, "2026-09-01T13:00:00Z", "2026-09-01T13:00:00Z");

        // Stops of the on-time trip: one served at 10:00 Lima inside 06:00-23:00, one outside 06:00-07:00.
        String inWindow = stop(COMPANY_A, tripOnTime, 1, "06:00", "23:00", DAY + "T15:00:00Z");
        String outOfWindow = stop(COMPANY_A, tripOnTime, 2, "06:00", "07:00", DAY + "T15:30:00Z");
        String orderFull = order(COMPANY_A);
        String orderShort = order(COMPANY_A);
        assign(COMPANY_A, tripOnTime, orderFull, "600");
        delivery(COMPANY_A, tripOnTime, inWindow, orderFull, "DELIVERED");
        delivery(COMPANY_A, tripOnTime, outOfWindow, orderShort, "PARTIAL");
        execute("INSERT INTO tms.trip_exception (company_id, trip_id, exception_type, reported_at, reported_by)"
                + " VALUES ('" + COMPANY_A + "', '" + tripOnTime + "', 'TRAFFIC_DELAY', '" + DAY + "T14:00:00Z', '"
                + adminUserId + "')");
        document(COMPANY_A, tripOnTime, true);
        document(COMPANY_A, tripOnTime, false);

        // Offers: TRLIT accepted the on-time trip in 30 minutes; TRAND refused the late one in 90 and
        // let a second offer lapse.
        tender(COMPANY_A, tripOnTime, trlit, 1, "ACCEPTED", DAY + "T10:00:00Z", DAY + "T10:30:00Z");
        tender(COMPANY_A, tripLate, trand, 1, "REJECTED", DAY + "T09:00:00Z", DAY + "T10:30:00Z");
        tender(COMPANY_A, tripLate, trand, 2, "EXPIRED", DAY + "T10:40:00Z", null);

        // Cost: 1200 actual on the on-time trip (600 kg), 500 estimated on the late one (no weight).
        cost(COMPANY_A, tripOnTime, "1000.00", "1200.00");
        cost(COMPANY_A, tripLate, "500.00", null);

        // Company B: a trip of a carrier of its own, which company A must never see.
        String other = carrier(COMPANY_B, "TRBEE", "Other Company Carrier");
        departedTrip(COMPANY_B, other, vehicle(COMPANY_B, other), DAY + "T13:00:00Z", DAY + "T13:00:00Z");
    }

    private String adminToken;
    private String viewerToken;
    private String monitorToken;
    private String fleetToken;

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
        monitorToken = TestJwts.validFor(MONITOR_AUTH);
        fleetToken = TestJwts.validFor(FLEET_AUTH);
    }

    @Test
    @DisplayName("each figure is the hand count; no denominator is null, not 0%; nothing of another company")
    void figures() throws Exception {
        String carrierA = "$.carriers[?(@.carrierKey == '" + trlit + "')]";
        String carrierB = "$.carriers[?(@.carrierKey == '" + trand + "')]";
        String own = "$.carriers[?(@.carrierKey == 'OWN_FLEET')]";
        mockMvc.perform(as(adminToken, get(SCORECARD + RANGE), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(11))
                .andExpect(jsonPath("$.unattributedTrips").value(1))
                .andExpect(jsonPath("$.carriers.length()").value(3))
                .andExpect(jsonPath(carrierA + ".carrierName").value("Transportes Litoral"))
                .andExpect(jsonPath(carrierA + ".trips").value(2))
                .andExpect(jsonPath(carrierA + ".tripsExecuted").value(2))
                .andExpect(jsonPath(carrierA + ".onTimeDeparturePercent").value(50.0))
                .andExpect(jsonPath(carrierA + ".serviceWindowsMeasured").value(2))
                .andExpect(jsonPath(carrierA + ".inWindowPercent").value(50.0))
                .andExpect(jsonPath(carrierA + ".deliveriesRecorded").value(2))
                .andExpect(jsonPath(carrierA + ".deliveriesShort").value(1))
                .andExpect(jsonPath(carrierA + ".deliverySuccessPercent").value(50.0))
                .andExpect(jsonPath(carrierA + ".exceptions").value(1))
                .andExpect(jsonPath(carrierA + ".exceptionsPer100Trips").value(50.0))
                .andExpect(jsonPath(carrierA + ".tenders.acceptancePercent").value(100.0))
                .andExpect(jsonPath(carrierA + ".tenders.averageResponseMinutes").value(30.0))
                .andExpect(jsonPath(carrierA + ".documents.deliveredPercent").value(100.0))
                .andExpect(jsonPath(carrierA + ".documents.cargoReturnPercent").value(50.0))
                .andExpect(jsonPath(carrierA + ".cost[0].currency").value("PEN"))
                .andExpect(jsonPath(carrierA + ".cost[0].tripsCosted").value(2))
                .andExpect(jsonPath(carrierA + ".cost[0].amount").value(1700.00))
                .andExpect(jsonPath(carrierA + ".cost[0].costPerTrip").value(850.00))
                .andExpect(jsonPath(carrierA + ".cost[0].costPerKg").value(2.0))
                // TRAND ran nothing in the range and is here for its offers: refused one, let one lapse.
                .andExpect(jsonPath(carrierB + ".trips").value(0))
                .andExpect(jsonPath(carrierB + ".onTimeDeparturePercent").value(NULL_IN_FILTER))
                .andExpect(jsonPath(carrierB + ".tenders.offered").value(2))
                .andExpect(jsonPath(carrierB + ".tenders.expired").value(1))
                .andExpect(jsonPath(carrierB + ".tenders.acceptancePercent").value(0.0))
                .andExpect(jsonPath(carrierB + ".tenders.averageResponseMinutes").value(90.0))
                .andExpect(jsonPath(own + ".ownFleet").value(true))
                .andExpect(jsonPath(own + ".trips").value(1))
                .andExpect(jsonPath(own + ".tripsExecuted").value(0))
                .andExpect(jsonPath(own + ".onTimeDeparturePercent").value(NULL_IN_FILTER))
                .andExpect(content().string(not(containsString("Other Company Carrier"))));

        // The other company sees its own carrier and nothing of A's.
        mockMvc.perform(as(adminToken, get(SCORECARD + RANGE), COMPANY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carriers.length()").value(1))
                .andExpect(jsonPath("$.carriers[0].carrierName").value("Other Company Carrier"))
                .andExpect(content().string(not(containsString("Transportes Litoral"))));
    }

    @Test
    @DisplayName("invoices come per currency from settlement, with the discrepancy rate the match produced")
    void invoices() throws Exception {
        String invoice = invoice(COMPANY_A, trlit, tripOnTime, "1200.00");
        String matched = mockMvc.perform(as(adminToken, post(INVOICES + "/" + invoice + "/match"), COMPANY_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        double expectedRate = "DISCREPANCY".equals(JsonPath.read(matched, "$.status")) ? 100.0 : 0.0;

        String carrierA = "$.carriers[?(@.carrierKey == '" + trlit + "')]";
        mockMvc.perform(as(adminToken, get(SCORECARD + RANGE), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicesVisible").value(true))
                .andExpect(jsonPath(carrierA + ".invoices[0].currency").value("PEN"))
                .andExpect(jsonPath(carrierA + ".invoices[0].invoices").value(1))
                .andExpect(jsonPath(carrierA + ".invoices[0].discrepancyRatePercent").value(expectedRate));
    }

    @Test
    @DisplayName("a viewer sees no offers and no cost; a monitor-only role sees no invoices and no documents")
    void finerPermissions() throws Exception {
        String carrierA = "$.carriers[?(@.carrierKey == '" + trlit + "')]";
        mockMvc.perform(as(viewerToken, get(SCORECARD + RANGE), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tendersVisible").value(false))
                .andExpect(jsonPath("$.costVisible").value(false))
                .andExpect(jsonPath("$.invoicesVisible").value(true))
                .andExpect(jsonPath(carrierA + ".trips").value(2))
                .andExpect(jsonPath(carrierA + ".tenders").value(NULL_IN_FILTER))
                .andExpect(jsonPath(carrierA + ".cost").value(NULL_IN_FILTER))
                // TRAND is known only by its offers, which a viewer may not see: no row at all.
                .andExpect(jsonPath("$.carriers[?(@.carrierKey == '" + trand + "')]").isEmpty());

        mockMvc.perform(as(monitorToken, get(SCORECARD + RANGE), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicesVisible").value(false))
                .andExpect(jsonPath("$.documentsVisible").value(false))
                .andExpect(jsonPath(carrierA + ".invoices").value(NULL_IN_FILTER))
                .andExpect(jsonPath(carrierA + ".documents").value(NULL_IN_FILTER))
                .andExpect(jsonPath(carrierA + ".onTimeDeparturePercent").value(50.0));
        mockMvc.perform(as(monitorToken, get(SCORECARD + "/export" + RANGE), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("invoices"))))
                .andExpect(content().string(not(containsString("tendersOffered"))));

        // No transport permission, no scorecard.
        mockMvc.perform(as(fleetToken, get(SCORECARD + RANGE), COMPANY_A)).andExpect(status().isForbidden());
        mockMvc.perform(as(fleetToken, get(SCORECARD + "/trips" + RANGE + "&carrier=" + trlit), COMPANY_A))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the drill-down lists the trips a row was counted from; a bad carrier is a 400")
    void drillDown() throws Exception {
        mockMvc.perform(as(adminToken, get(SCORECARD + "/trips" + RANGE + "&carrier=" + trlit), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrierName").value("Transportes Litoral"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.trips.length()").value(2))
                .andExpect(jsonPath("$.trips[?(@.tripId == '" + tripLate + "')].departureLate").value(true))
                .andExpect(jsonPath("$.trips[?(@.tripId == '" + tripOnTime + "')].departureLate").value(false))
                .andExpect(jsonPath("$.trips[?(@.tripId == '" + tripOnTime + "')].windowsMissed").value(1))
                .andExpect(jsonPath("$.trips[?(@.tripId == '" + tripOnTime + "')].deliveriesShort").value(1));
        mockMvc.perform(as(adminToken, get(SCORECARD + "/trips" + RANGE + "&carrier=OWN_FLEET"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownFleet").value(true))
                .andExpect(jsonPath("$.trips[0].departureLate").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(as(adminToken, get(SCORECARD + "/trips" + RANGE + "&carrier=TRLIT"), COMPANY_A))
                .andExpect(status().isBadRequest());
        // Another company's view of A's carrier: nothing.
        mockMvc.perform(as(adminToken, get(SCORECARD + "/trips" + RANGE + "&carrier=" + trlit), COMPANY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        // The range cap is the KPI report's.
        mockMvc.perform(as(adminToken, get(SCORECARD + "?from=2026-01-01&to=2026-10-20"), COMPANY_A))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the CSV is the screen's figures, named for its range, one operation row per carrier")
    void export() throws Exception {
        String csv = mockMvc.perform(as(adminToken, get(SCORECARD + "/export" + RANGE), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("tms-carrier-scorecard-2026-10-10-to-2026-10-20.csv")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        List<String> lines = csv.lines().toList();
        // The writer leads with a byte-order mark so a spreadsheet opens it as UTF-8.
        assertThat(lines.get(0).replace("\uFEFF", "")).startsWith("carrierKey,carrierCode,carrierName,ownFleet,currency,trips,")
                .contains("tenderAcceptancePercent").contains("costPerKg");
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith(trlit + ",TRLIT,Transportes Litoral,false,,2,"));
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith(trlit + ",TRLIT,Transportes Litoral,false,PEN,"));
        assertThat(csv).doesNotContain("Other Company Carrier");
    }

    // ------------------------------------------------------------------ fixture

    private static String carrier(UUID companyId, String code, String name) {
        return scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)"
                + " VALUES ('" + companyId + "', '" + code + "', '" + name + "', 'RUC', '20"
                + String.format("%09d", Math.abs(code.hashCode()) % 1_000_000_000) + "') RETURNING id");
    }

    private static String vehicle(UUID companyId, String carrierId) {
        int n = SEQUENCE.incrementAndGet();
        String type = scalar("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3,"
                + " max_pallets) VALUES ('" + companyId + "', 'SVT-" + n + "', 'Rigid', 10000, 40, 20) RETURNING id");
        return scalar("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate, carrier_id)"
                + " VALUES ('" + companyId + "', '" + type + "', 'SV-" + n + "', 'SCP-" + n + "', "
                + (carrierId == null ? "NULL" : "'" + carrierId + "'") + ") RETURNING id");
    }

    private static String run(UUID companyId, String date) {
        int n = SEQUENCE.incrementAndGet();
        String origin = location(companyId);
        return scalar("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date, status)"
                + " VALUES ('" + companyId + "', 'PL-SC-" + n + "', '" + origin + "', '" + date + "', 'DRAFT')"
                + " RETURNING id");
    }

    private static String departedTrip(UUID companyId, String carrierId, String vehicleId, String planned,
            String actual) {
        String date = planned.substring(0, 10);
        String confirmed = planned.substring(0, 11) + "06:00:00Z";
        String ready = planned.substring(0, 11) + "07:00:00Z";
        return scalar("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number, carrier_id,"
                + " vehicle_id, status, planned_departure_at, confirmed_at, actual_departure_at,"
                + " snapshot_max_weight_kg, snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at,"
                + " confirmed_by, dispatched_by, ready_at, ready_by, dispatch_source) VALUES ('" + companyId
                + "', '" + run(companyId, date) + "', '" + date + "', 1, " + quoted(carrierId) + ", '" + vehicleId
                + "', 'IN_TRANSIT', '" + planned + "', '" + confirmed + "', '" + actual + "', 10000, 40, 20, '"
                + confirmed + "', '" + adminUserId + "', '" + adminUserId + "', '" + ready + "', '" + adminUserId
                + "', 'OPERATOR') RETURNING id");
    }

    private static String confirmedTrip(UUID companyId, String carrierId, String vehicleId, String planned) {
        String date = planned.substring(0, 10);
        String confirmed = planned.substring(0, 11) + "06:00:00Z";
        return scalar("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number, carrier_id,"
                + " vehicle_id, status, planned_departure_at, confirmed_at, snapshot_max_weight_kg,"
                + " snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at, confirmed_by) VALUES ('"
                + companyId + "', '" + run(companyId, date) + "', '" + date + "', 1, " + quoted(carrierId) + ", '"
                + vehicleId + "', 'CONFIRMED', '" + planned + "', '" + confirmed + "', 10000, 40, 20, '" + confirmed
                + "', '" + adminUserId + "') RETURNING id");
    }

    private static String draftTrip(UUID companyId) {
        return scalar("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number) VALUES ('"
                + companyId + "', '" + run(companyId, DAY) + "', '" + DAY + "', 1) RETURNING id");
    }

    private static String stop(UUID companyId, String tripId, int sequence, String windowStart, String windowEnd,
            String arrival) {
        return scalar("INSERT INTO tms.trip_stop (company_id, trip_id, destination_id, sequence, service_window_start,"
                + " service_window_end, execution_status, actual_arrival_at, actual_departure_at) VALUES ('"
                + companyId + "', '" + tripId + "', '" + location(companyId) + "', " + sequence + ", '" + windowStart
                + "', '" + windowEnd + "', 'COMPLETED', '" + arrival + "', '" + arrival + "') RETURNING id");
    }

    private static String location(UUID companyId) {
        int n = SEQUENCE.incrementAndGet();
        return scalar("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', 'SL-" + n
                + "', 'Place " + n + "') RETURNING id");
    }

    private static String order(UUID companyId) {
        int n = SEQUENCE.incrementAndGet();
        return scalar("INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id,"
                + " service_date, status, total_weight_kg, total_volume_m3, total_pallets) VALUES ('" + companyId
                + "', 'TO-SC-" + n + "', '" + location(companyId) + "', '" + location(companyId) + "', '" + DAY
                + "', 'READY_FOR_PLANNING', 600, 2, 1) RETURNING id");
    }

    private static void assign(UUID companyId, String tripId, String orderId, String weight) {
        execute("INSERT INTO tms.trip_order_assignment (company_id, trip_id, order_id, whole_order,"
                + " assigned_weight_kg, assigned_volume_m3, assigned_pallets) VALUES ('" + companyId + "', '"
                + tripId + "', '" + orderId + "', true, " + weight + ", 2, 1)");
    }

    private static void delivery(UUID companyId, String tripId, String stopId, String orderId, String result) {
        execute("INSERT INTO tms.order_delivery (company_id, trip_id, trip_stop_id, order_id, result, source,"
                + " delivered_at, actor_app_user_id, notes) VALUES ('" + companyId + "', '" + tripId + "', '" + stopId
                + "', '" + orderId + "', '" + result + "', 'OPERATOR', '" + DAY + "T15:10:00Z', '" + adminUserId
                + "', 'Scorecard fixture')");
    }

    private static void document(UUID companyId, String tripId, boolean returned) {
        int n = SEQUENCE.incrementAndGet();
        String document = scalar("INSERT INTO tms.logistics_document (company_id, source_system, document_type,"
                + " document_number) VALUES ('" + companyId + "', 'WMS', 'REMISSION_GUIDE', 'GR-SC-" + n
                + "') RETURNING id");
        execute("INSERT INTO tms.document_delivery (company_id, trip_id, document_id, document_type, document_number,"
                + " status, reported_at, recorded_at, returned_at, returned_by) VALUES ('" + companyId + "', '"
                + tripId + "', '" + document + "', 'REMISSION_GUIDE', 'GR-SC-" + n + "', 'DELIVERED', '" + DAY
                + "T15:20:00Z', '" + DAY + "T15:21:00Z', "
                + (returned ? "'" + DAY + "T20:00:00Z', '" + adminUserId + "'" : "NULL, NULL") + ")");
    }

    private static void tender(UUID companyId, String tripId, String carrierId, int attempt, String status,
            String sentAt, String respondedAt) {
        boolean answered = "ACCEPTED".equals(status) || "REJECTED".equals(status);
        String expires = sentAt.substring(0, 11) + "12:00:00Z";
        execute("INSERT INTO tms.trip_tender (company_id, trip_id, carrier_id, attempt, status, expires_at,"
                + " created_by, sent_at, sent_by, responded_at, response_source, responded_by, response_notes,"
                + " expired_at) VALUES ('" + companyId + "', '" + tripId + "', '" + carrierId + "', " + attempt
                + ", '" + status + "', '" + expires + "', '" + adminUserId + "', '" + sentAt + "', '" + adminUserId
                + "', " + (answered ? "'" + respondedAt + "', 'OPERATOR', '" + adminUserId + "'" : "NULL, NULL, NULL")
                + ", " + ("REJECTED".equals(status) ? "'No unit available'" : "NULL") + ", "
                + ("EXPIRED".equals(status) ? "'" + expires + "'" : "NULL") + ")");
    }

    private static void cost(UUID companyId, String tripId, String estimated, String actual) {
        if (rateCard == null) {
            rateCard = scalar("INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency,"
                    + " base_amount, valid_from) VALUES ('" + companyId + "', 'RC-SC', 'Card SC', '"
                    + trlit + "', 'CARRIER', 'PEN', 100.00, '2026-01-01') RETURNING id");
        }
        String card = rateCard;
        String n = "";
        execute("INSERT INTO tms.trip_cost (company_id, trip_id, planning_date, currency, estimated_amount,"
                + " estimated_at, rate_card_id, rate_card_code, rate_card_scope, actual_amount, actual_recorded_at,"
                + " actual_recorded_by) VALUES ('" + companyId + "', '" + tripId + "', '" + DAY + "', 'PEN', "
                + estimated + ", now(), '" + card + "', 'RC-SC" + n + "', 'CARRIER', "
                + (actual == null ? "NULL, NULL, NULL" : actual + ", now(), '" + adminUserId + "'") + ")");
    }

    /** Receives an invoice billing one trip, as the admin; returns its id. */
    private String invoice(UUID companyId, String carrierId, String trip, String amount) throws Exception {
        String line = "{\"tripId\":\"" + trip + "\",\"description\":\"Line\",\"lineAmount\":" + amount + "}";
        String body = "{\"carrierId\":\"" + carrierId + "\",\"invoiceNumber\":\"SC-" + SEQUENCE.incrementAndGet()
                + "\",\"invoiceDate\":\"" + DAY + "\",\"currency\":\"PEN\",\"totalAmount\":" + amount
                + ",\"subtotalAmount\":" + amount + ",\"taxRate\":0,\"taxAmount\":0"
                + ",\"lines\":[" + line + "]}";
        String response = mockMvc.perform(as(adminToken, post(INVOICES), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
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

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder,
            UUID companyId) {
        return builder.header("Authorization", "Bearer " + token)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String quoted(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String scalar(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("no row for: " + sql);
            }
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the scorecard fixture: " + sql, failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the scorecard fixture: " + sql, failed);
        }
    }
}
