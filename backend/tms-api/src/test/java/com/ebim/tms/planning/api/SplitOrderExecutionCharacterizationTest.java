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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * <b>Characterisation, not specification.</b> Pins what the product does today when an order that
 * was split across trips (V37) meets execution (V25/V36), so that the fix for it is a visible,
 * reviewed change of these assertions rather than a silent one.
 *
 * <p>Every assertion here records <em>current</em> behaviour. The ones marked {@code DEFECT} are
 * behaviour the approved rule refuses - "dispatching a partial allocation must never stop the rest
 * of the order from staying plannable" - and are analysed in
 * {@code docs/domain/SPLIT_ORDER_EXECUTION.md}. When that document's model lands, the marked
 * assertions change in the same commit as the code, and nothing else here should have to.
 *
 * <p>The five scenarios are the ones the Phase 0 brief names, numbered the same way.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SplitOrderExecutionCharacterizationTest.JwtDecoderOverride.class)
class SplitOrderExecutionCharacterizationTest {

    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";
    private static final String ORDERS = "/api/v1/orders";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String destination;
    private static String carrier;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_split_execution");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'SPX-ORG', 'Split Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'SPX-A', 'Split Company', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'spx.admin@example.invalid', 'SPX Admin');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'spx.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'spx.admin@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, ADMIN_AUTH, ORGANIZATION, COMPANY, COMPANY));
        origin = insertLocation("SPX-ORIGIN", "ORIGIN");
        destination = insertLocation("SPX-DEST", "DESTINATION");
        carrier = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY + "', 'SPX-CARR', 'Split Carrier', 'RUC', '20100000009')");
    }

    @BeforeEach
    void mintToken() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
    }

    // --- 1. partial allocation + remainder unallocated + first trip dispatch ---------

    @Test
    @DisplayName("1. a trip carrying 60 of a 100-pallet order, with 40 unplanned, cannot be dispatched (DEFECT)")
    void firstTripOfAPartlyPlannedOrderCannotLeave() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());

        // The rest of the order is still plannable before departure - V37 works as designed.
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertPending(order, "40");

        // DEFECT: OrderPlanningService.markInExecution refuses READY_FOR_PLANNING, and dispatch runs
        // it for every active order in the same transaction - so the whole departure rolls back.
        transition(tripA, "dispatch")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "is READY_FOR_PLANNING and cannot be dispatched")));

        assertThat(tripStatus(tripA)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "60");
    }

    // --- 2. partial allocations already distributed across 2 trips + first dispatch ---

    @Test
    @DisplayName("2. a fully split order (60 + 40): the first trip leaves, and closing it out empties the "
            + "ledger while the second trip still carries 40 (DEFECT)")
    void closingOneHalfOfASplitReleasesTheOtherHalfToo() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String tripB = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        confirm(run).andExpect(status().isOk());

        // Fully allocated, so the order is PLANNED and the first departure is accepted.
        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");
        assertAllocatedMatchesLedger(order, "100");

        // Trip A delivers its 60 in full and closes out; trip B has not left yet.
        String stopA = stopIdsOf(tripA).get(0);
        stopAction(tripA, stopA, "arrive").andExpect(status().isOk());
        stopAction(tripA, stopA, "complete").andExpect(status().isOk());
        recordDelivered(tripA, stopA, order).andExpect(status().isOk());
        transition(tripA, "complete").andExpect(status().isOk());

        // DEFECT (a): the order is closed out as DELIVERED from trip A's row alone, while 40
        // pallets have not left the dock.
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        // DEFECT (b): TransportOrder.closeOut zeroes the whole running total, so the ledger's
        // "allocated = what is on trips that have not closed out" no longer holds: trip B has not
        // left and still carries 40, but the order counts nothing allocated. (Assignment rows are
        // not closed by a trip's completion - trip A's 60 stay ACTIVE as history - so the fair
        // comparison is with the rows on trips that are still open.)
        assertThat(new BigDecimal(allocated(order))).isEqualByComparingTo("0");
        assertThat(new BigDecimal(openTripLedgerSum(order))).isEqualByComparingTo("40");
        assertThat(new BigDecimal(ledgerSum(order))).isEqualByComparingTo("100");

        // Trip B still departs, and the order does not move: markInExecution is a no-op on a
        // closed-out order, so it stays DELIVERED with 40 pallets on the road.
        transition(tripB, "ready").andExpect(status().isOk());
        transition(tripB, "dispatch").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
    }

    // --- 3. one trip delivered while the remainder is still unplanned -----------------

    @Test
    @DisplayName("3. 'one trip delivered while the rest is unplanned' is unreachable today: the trip never leaves")
    void deliveringOneTripWhileTheRestIsUnplannedIsUnreachable() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());

        // The remainder can be planned on a second run for the same day while trip A waits...
        String secondRun = newRun(date);
        String tripB = newTrip(secondRun, date);
        assignPart(tripB, order, "200", "2", "20").andExpect(status().isOk());
        assertPending(order, "20");

        // ...but trip A is stuck at the dock for as long as anything of the order is unplanned,
        // so no delivery can ever be recorded against it. Scenario 1's defect is what hides this one.
        transition(tripA, "dispatch").andExpect(status().isConflict());
        assertThat(tripStatus(tripA)).isEqualTo("READY_FOR_DISPATCH");
    }

    // --- 4. cancel after partial allocation --------------------------------------------

    @Test
    @DisplayName("4. an order with 60 of 100 on a draft trip can be cancelled, leaving its allocation live (DEFECT)")
    void aPartlyPlannedOrderCanBeCancelledUnderItsTrip() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());

        // DEFECT: OrderService.cancel checks the status only, and a part-allocated order is
        // READY_FOR_PLANNING - so it is cancelled with an ACTIVE assignment still under it.
        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/cancel")).param("reason", "customer withdrew"))
                .andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertAllocatedMatchesLedger(order, "60");
        assertThat(activeAssignmentRows(order)).isEqualTo(1);
    }

    @Test
    @DisplayName("4b. an order with 60 of 100 on a draft trip can be edited back to NOT_READY under its trip (DEFECT)")
    void aPartlyPlannedOrderCanBeEditedUnderItsTrip() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());

        // DEFECT, same root as scenario 4: READY_FOR_PLANNING is editable, and any edit resets the
        // order to NOT_READY (TransportOrder.applyChanges) - with an ACTIVE assignment still under it.
        long version = Long.parseLong(queryString(
                "SELECT version FROM tms.transport_order WHERE id = '" + order + "'"));
        mockMvc.perform(asAdmin(put(ORDERS + "/" + order))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originId":"%s","destinationId":"%s","serviceDate":"%s","priority":"URGENT",
                                 "declaredWeightKg":1000,"declaredVolumeM3":10,"declaredPallets":100,
                                 "version":%d,"lines":[]}
                                """.formatted(origin, destination, date, version)))
                .andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("NOT_READY");
        assertAllocatedMatchesLedger(order, "60");
    }

    // --- 5. remove / cancel a trip containing a partial allocation ---------------------

    @Test
    @DisplayName("5a. removing a partial allocation from a draft trip returns exactly that share")
    void removingAPartialAllocationReturnsOnlyItsShare() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        mockMvc.perform(asAdmin(delete(TRIPS + "/" + tripA + "/assignments/" + order)))
                .andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "0");
        assertPending(order, "100");
    }

    @Test
    @DisplayName("5b. cancelling a confirmed trip that carries a partial allocation returns that share to the pool")
    void cancellingATripWithAPartialAllocationReturnsItsShare() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String tripB = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PLANNED");

        mockMvc.perform(asAdmin(post(TRIPS + "/" + tripA + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + versionOfTrip(tripA) + ",\"reason\":\"truck broke down\"}"))
                .andExpect(status().isOk());

        // Only trip A's 60 come back; trip B's 40 stay booked, and the order is plannable again.
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "40");
        assertPending(order, "60");
    }

    // --- helpers -----------------------------------------------------------------------

    private ResultActions transition(String tripId, String action) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOfTrip(tripId) + "}"));
    }

    private ResultActions stopAction(String tripId, String stopId, String action) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/stops/" + stopId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private ResultActions recordDelivered(String tripId, String stopId, String orderId) throws Exception {
        return mockMvc.perform(asAdmin(put(TRIPS + "/" + tripId + "/stops/" + stopId + "/orders/" + orderId
                        + "/delivery"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"DELIVERED\",\"deliveredAt\":\"" + OffsetDateTime.now() + "\"}"));
    }

    private List<String> stopIdsOf(String tripId) throws Exception {
        String body = mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.stops[*].id");
    }

    private long versionOfTrip(String tripId) throws Exception {
        String body = mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(body, "$.trip.version");
        return version.longValue();
    }

    private ResultActions assign(String tripId, String orderId) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\"}"));
    }

    private ResultActions assignPart(String tripId, String orderId, String weightKg, String volumeM3,
            String pallets) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"weightKg\":" + weightKg + ",\"volumeM3\":" + volumeM3
                        + ",\"pallets\":" + pallets + "}"));
    }

    private ResultActions confirm(String runId) throws Exception {
        return mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + runId + "/confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"));
    }

    private String newRun(LocalDate date) throws Exception {
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + origin + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.run.id");
    }

    private String newTrip(String runId, LocalDate date) throws Exception {
        String departure = date.atTime(8, 0).atZone(LIMA).toOffsetDateTime().toString();
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + runId + "/trips"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle() + "\",\"plannedDepartureAt\":\"" + departure
                                + "\",\"version\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.trip.id");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, COMPANY.toString());
    }

    private static void assertPending(String orderId, String expectedPallets) {
        BigDecimal ordered = new BigDecimal(queryString(
                "SELECT total_pallets FROM tms.transport_order WHERE id = '" + orderId + "'"));
        assertThat(ordered.subtract(new BigDecimal(allocated(orderId)))).isEqualByComparingTo(expectedPallets);
    }

    /** The running total (V37) and the ACTIVE ledger it summarises, asserted to agree. */
    private static void assertAllocatedMatchesLedger(String orderId, String expectedPallets) {
        assertThat(new BigDecimal(allocated(orderId))).isEqualByComparingTo(expectedPallets);
        assertThat(new BigDecimal(ledgerSum(orderId))).isEqualByComparingTo(expectedPallets);
    }

    private static String allocated(String orderId) {
        return queryString("SELECT allocated_pallets FROM tms.transport_order WHERE id = '" + orderId + "'");
    }

    private static String ledgerSum(String orderId) {
        return queryString("SELECT coalesce(sum(assigned_pallets), 0) FROM tms.trip_order_assignment"
                + " WHERE order_id = '" + orderId + "' AND status = 'ACTIVE'");
    }

    /** The ACTIVE ledger restricted to trips that have not been closed out or cancelled. */
    private static String openTripLedgerSum(String orderId) {
        return queryString("SELECT coalesce(sum(a.assigned_pallets), 0) FROM tms.trip_order_assignment a"
                + " JOIN tms.trip t ON t.id = a.trip_id"
                + " WHERE a.order_id = '" + orderId + "' AND a.status = 'ACTIVE'"
                + " AND t.status NOT IN ('COMPLETED', 'CANCELLED')");
    }

    private static long activeAssignmentRows(String orderId) {
        return Long.parseLong(queryString("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '"
                + orderId + "' AND status = 'ACTIVE'"));
    }

    private static String orderStatus(String orderId) {
        return queryString("SELECT status FROM tms.transport_order WHERE id = '" + orderId + "'");
    }

    private static String tripStatus(String tripId) {
        return queryString("SELECT status FROM tms.trip WHERE id = '" + tripId + "'");
    }

    private static LocalDate nextDate() {
        return LocalDate.of(2026, 6, 1).plusDays(SEQUENCE.incrementAndGet());
    }

    /** A 100-pallet order, divisible on all three measures in the same proportion. */
    private static String order(LocalDate serviceDate) {
        return insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets) VALUES ('"
                + COMPANY + "', 'TO-SPX-" + String.format(Locale.ROOT, "%06d", SEQUENCE.incrementAndGet()) + "', '"
                + origin + "', '" + destination + "', '" + serviceDate + "', 'READY_FOR_PLANNING', 1000, 10, 100)");
    }

    private static String vehicle() {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY + "', 'SPX-TYPE-" + number
                + "', 'Split type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY + "', 'SPX-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "SPX-%05d", number) + "', '" + carrier + "', '" + type + "')");
    }

    private static String insertLocation(String code, String role) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY
                + "', '" + code + "', '" + code + "')");
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
            throw new IllegalStateException("could not seed a split-execution fixture", failed);
        }
    }

    private static String queryString(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the split-execution fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the split-execution fixture", failed);
        }
    }
}
