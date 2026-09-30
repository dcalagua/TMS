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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
 * <b>Specification</b> of what happens when an order split across trips (V37) meets execution
 * (V25/V36): rules R1-R3 of {@code docs/domain/SPLIT_ORDER_EXECUTION.md}, approved 2026-09-27.
 *
 * <p>This started as a characterisation of the defects (every assertion then marked {@code DEFECT}
 * is now the approved behaviour). The rule under all of it:
 *
 * <blockquote>Dispatching a partial allocation must never stop the rest of the order from staying
 * plannable.</blockquote>
 *
 * <p>Numbered as the approval lists them: 1-10.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SplitOrderExecutionTest.JwtDecoderOverride.class)
class SplitOrderExecutionTest {

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

    // --- 1. 100 total / A = 60 / 40 unplanned / A departs --------------------------------

    @Test
    @DisplayName("1. R1: trip A carrying 60 of 100 departs; the order stays plannable with 40 pending")
    void firstTripOfAPartlyPlannedOrderLeavesAndTheRemainderStaysPlannable() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());

        assertThat(tripStatus(tripA)).isEqualTo("IN_TRANSIT");
        // Not IN_EXECUTION: the order only enters it from PLANNED, and 40 are still to place.
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "60");
        assertPending(order, "40");
        assertEligible(order, date, true);

        // The remainder is plannable for real: a second trip takes it, and the order is PLANNED.
        String secondRun = newRun(date);
        String tripB = newTrip(secondRun, date);
        assign(tripB, order).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        assertAllocatedMatchesLedger(order, "100");
    }

    // --- 2. 100 total / A = 60 / B = 40 / A departs ------------------------------------

    @Test
    @DisplayName("2. R2: a fully split order (60 + 40): A leaves and closes, the order waits for B, B closes it")
    void aFullySplitOrderClosesOnlyWithItsLastCarrier() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String tripB = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        confirm(run).andExpect(status().isOk());

        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");
        assertAllocatedMatchesLedger(order, "100");

        // 3. A delivers and closes while B has not left: the order is not closed by its first trip.
        deliverWholeStop(tripA, order, "DELIVERED", quantities("600", "6", "60", "600", "6", "60"));
        transition(tripA, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");
        assertThat(new BigDecimal(allocated(order))).isEqualByComparingTo("100");

        transition(tripB, "ready").andExpect(status().isOk());
        transition(tripB, "dispatch").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");

        // 8. The last carrier closes it, from the deliveries of both trips: 60 + 40 = all of it.
        deliverWholeStop(tripB, order, "DELIVERED", quantities("400", "4", "40", "400", "4", "40"));
        transition(tripB, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        assertThat(new BigDecimal(allocated(order))).isEqualByComparingTo("0");
    }

    // --- 3/4. A delivered while the remainder is still unplanned -----------------------

    @Test
    @DisplayName("3/4. A delivered and closed with 40 never planned: the 40 stay plannable, and their trip closes the order")
    void deliveringOneTripWhileTheRestIsUnplannedKeepsTheRest() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());
        deliverWholeStop(tripA, order, "DELIVERED", quantities("600", "6", "60", "600", "6", "60"));
        transition(tripA, "complete").andExpect(status().isOk());

        // The first trip is over, and nothing about it closed the order or consumed the remainder.
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertPending(order, "40");
        assertEligible(order, date, true);

        String secondRun = newRun(date);
        String tripB = newTrip(secondRun, date);
        assign(tripB, order).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        confirmRun(secondRun);
        transition(tripB, "ready").andExpect(status().isOk());
        transition(tripB, "dispatch").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");
        deliverWholeStop(tripB, order, "DELIVERED", quantities("400", "4", "40", "400", "4", "40"));
        transition(tripB, "complete").andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        assertEligible(order, date, false);
    }

    // --- 5. cancel with a partial allocation --------------------------------------------

    @Test
    @DisplayName("5. R3: an order with 60 of 100 on a trip cannot be cancelled until that share is unassigned")
    void aPartlyPlannedOrderCannotBeCancelledUnderItsTrip() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/cancel")).param("reason", "customer withdrew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("unassign")));

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "60");
        assertThat(activeAssignmentRows(order)).isEqualTo(1);

        // The way out: take the share off its trip, then cancel.
        mockMvc.perform(asAdmin(delete(TRIPS + "/" + tripA + "/assignments/" + order)))
                .andExpect(status().isOk());
        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/cancel")).param("reason", "customer withdrew"))
                .andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertAllocatedMatchesLedger(order, "0");
    }

    @Test
    @DisplayName("5b. R3: an order whose 60 are already on the road cannot be cancelled either")
    void aPartlyDepartedOrderCannotBeCancelled() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());

        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/cancel")).param("reason", "customer withdrew"))
                .andExpect(status().isConflict());
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
    }

    // --- 6. edit with a partial allocation ----------------------------------------------

    @Test
    @DisplayName("6. R3: an order with 60 of 100 on a trip cannot be edited; without it, it can")
    void aPartlyPlannedOrderCannotBeEditedUnderItsTrip() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());

        edit(order, date)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("part of it on a trip")));
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "60");

        mockMvc.perform(asAdmin(delete(TRIPS + "/" + tripA + "/assignments/" + order)))
                .andExpect(status().isOk());
        edit(order, date).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("NOT_READY");
        assertAllocatedMatchesLedger(order, "0");
    }

    // --- 7. remove / cancel a trip containing a partial allocation ---------------------

    @Test
    @DisplayName("7a. removing a partial allocation from a draft trip returns exactly that share")
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
    @DisplayName("7b. cancelling a confirmed trip that carries a partial allocation returns that share to the pool")
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

        cancelTrip(tripA).andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "40");
        assertPending(order, "60");
    }

    @Test
    @DisplayName("7c. A (60) has left and the order is IN_EXECUTION; cancelling B (40) puts the 40 back in the pool")
    void cancellingTheUndepartedHalfReturnsItEvenAfterTheOrderIsInExecution() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String tripB = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");

        cancelTrip(tripB).andExpect(status().isOk());

        // The rule: the remainder never disappears. The departed 60 stay allocated to A.
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertAllocatedMatchesLedger(order, "60");
        assertPending(order, "40");
        assertEligible(order, date, true);

        // And A closing out does not close the order while the 40 wait for a truck.
        deliverWholeStop(tripA, order, "DELIVERED", quantities("600", "6", "60", "600", "6", "60"));
        transition(tripA, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
        assertPending(order, "40");
    }

    // --- 9. a partial / rejected delivery on one of several trips ----------------------

    @Test
    @DisplayName("9. A delivers 60, B's 40 are refused: the order closes PARTIALLY_DELIVERED and is reopenable")
    void aRefusalOnOneOfTwoTripsClosesTheOrderAsPartial() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String tripB = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        for (String trip : List.of(tripA, tripB)) {
            transition(trip, "ready").andExpect(status().isOk());
            transition(trip, "dispatch").andExpect(status().isOk());
        }

        // B finishes first, with a refusal; A is still on the road, so nothing closes yet.
        deliverWholeStop(tripB, order, "REJECTED", quantities("400", "4", "40", "0", "0", "0"));
        transition(tripB, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_EXECUTION");

        deliverWholeStop(tripA, order, "DELIVERED", quantities("600", "6", "60", "600", "6", "60"));
        transition(tripA, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PARTIALLY_DELIVERED");

        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/reopen")).param("reason", "redeliver the 40"))
                .andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_PLANNING");
    }

    @Test
    @DisplayName("9b. per-trip ceiling, order-wide ceiling: 70 delivered, reopened and replanned whole, only 30 more fit")
    void aReplannedOrderCannotBeDeliveredBeyondItsDemand() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);
        assign(tripA, order).andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());
        transition(tripA, "dispatch").andExpect(status().isOk());
        deliverWholeStop(tripA, order, "PARTIAL", quantities("1000", "10", "100", "700", "7", "70"));
        transition(tripA, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("PARTIALLY_DELIVERED");
        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/reopen")).param("reason", "the other 30"))
                .andExpect(status().isOk());

        String secondRun = newRun(date);
        String tripB = newTrip(secondRun, date);
        assign(tripB, order).andExpect(status().isOk());
        confirmRun(secondRun);
        transition(tripB, "ready").andExpect(status().isOk());
        transition(tripB, "dispatch").andExpect(status().isOk());
        String stop = stopIdsOf(tripB).get(0);
        stopAction(tripB, stop, "arrive").andExpect(status().isOk());

        // B's own share is the whole order again, but the order only ever asked for 100.
        mockMvc.perform(asAdmin(put(TRIPS + "/" + tripB + "/stops/" + stop + "/orders/" + order + "/delivery"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"DELIVERED\",\"deliveredAt\":\"" + OffsetDateTime.now()
                                + "\",\"quantities\":" + quantities("400", "4", "40", "400", "4", "40") + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("what was ordered")));
        mockMvc.perform(asAdmin(put(TRIPS + "/" + tripB + "/stops/" + stop + "/orders/" + order + "/delivery"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"DELIVERED\",\"deliveredAt\":\"" + OffsetDateTime.now()
                                + "\",\"quantities\":" + quantities("300", "3", "30", "300", "3", "30") + "}"))
                .andExpect(status().isOk());
        stopAction(tripB, stop, "complete").andExpect(status().isOk());
        transition(tripB, "complete").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
    }

    // --- 10. concurrency -----------------------------------------------------------------

    @RepeatedTest(3)
    @DisplayName("10a. both carriers of a split order completing at the same instant: the order is closed exactly once")
    void twoCarriersClosingAtOnceStillCloseTheOrder() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String tripB = newTrip(run, date);
        String order = order(date);

        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        assign(tripB, order).andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        for (String trip : List.of(tripA, tripB)) {
            transition(trip, "ready").andExpect(status().isOk());
            transition(trip, "dispatch").andExpect(status().isOk());
        }
        deliverWholeStop(tripA, order, "DELIVERED", quantities("600", "6", "60", "600", "6", "60"));
        deliverWholeStop(tripB, order, "DELIVERED", quantities("400", "4", "40", "400", "4", "40"));
        long versionA = versionOfTrip(tripA);
        long versionB = versionOfTrip(tripB);

        List<Integer> statuses = concurrently(
                () -> completeAt(tripA, versionA), () -> completeAt(tripB, versionB));

        assertThat(statuses).containsOnly(200);
        // Without the lock-then-read order, each would see the other still IN_TRANSIT and both
        // leave the order IN_EXECUTION for ever.
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        assertThat(new BigDecimal(allocated(order))).isEqualByComparingTo("0");
    }

    // Repeated: before the lock-then-refresh in TransportOrderLocking, 10 races in 12 lost the departure.
    @RepeatedTest(5)
    @DisplayName("10b. planning the remainder while the first share departs: both succeed and the ledger stays exact")
    void planningTheRemainderWhileTheFirstShareDeparts() throws Exception {
        LocalDate date = nextDate();
        String run = newRun(date);
        String tripA = newTrip(run, date);
        String order = order(date);
        assignPart(tripA, order, "600", "6", "60").andExpect(status().isOk());
        confirm(run).andExpect(status().isOk());
        transition(tripA, "ready").andExpect(status().isOk());
        String secondRun = newRun(date);
        String tripB = newTrip(secondRun, date);
        long versionA = versionOfTrip(tripA);

        List<Integer> statuses = concurrently(
                () -> mockMvc.perform(asAdmin(post(TRIPS + "/" + tripA + "/dispatch"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":" + versionA + "}"))
                        .andReturn().getResponse().getStatus(),
                () -> assign(tripB, order).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(tripStatus(tripA)).isEqualTo("IN_TRANSIT");
        assertAllocatedMatchesLedger(order, "100");
        // Whichever went first, the order ends where the plan says: nothing left to place. It is
        // IN_EXECUTION if the departure found it PLANNED, and PLANNED if it found it still pending.
        assertThat(orderStatus(order)).isIn("PLANNED", "IN_EXECUTION");
        assertPending(order, "0");
    }

    // --- helpers -----------------------------------------------------------------------

    private ResultActions transition(String tripId, String action) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOfTrip(tripId) + "}"));
    }

    private ResultActions cancelTrip(String tripId) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/cancel"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + versionOfTrip(tripId) + ",\"reason\":\"truck broke down\"}"));
    }

    private int completeAt(String tripId, long version) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andReturn().getResponse().getStatus();
    }

    private ResultActions edit(String orderId, LocalDate date) throws Exception {
        long version = Long.parseLong(queryString(
                "SELECT version FROM tms.transport_order WHERE id = '" + orderId + "'"));
        return mockMvc.perform(asAdmin(put(ORDERS + "/" + orderId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originId":"%s","destinationId":"%s","serviceDate":"%s","priority":"URGENT",
                         "declaredWeightKg":1000,"declaredVolumeM3":10,"declaredPallets":100,
                         "version":%d,"lines":[]}
                        """.formatted(origin, destination, date, version)));
    }

    /** Arrives at the trip's only stop, records the order's outcome, and completes the stop. */
    private void deliverWholeStop(String tripId, String orderId, String result, String quantities) throws Exception {
        String stop = stopIdsOf(tripId).get(0);
        stopAction(tripId, stop, "arrive").andExpect(status().isOk());
        mockMvc.perform(asAdmin(put(TRIPS + "/" + tripId + "/stops/" + stop + "/orders/" + orderId + "/delivery"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"" + result + "\",\"deliveredAt\":\"" + OffsetDateTime.now()
                                + "\",\"notes\":\"split-order scenario\",\"quantities\":" + quantities + "}"))
                .andExpect(status().isOk());
        stopAction(tripId, stop, "complete").andExpect(status().isOk());
    }

    private static String quantities(String attemptedKg, String attemptedM3, String attemptedPallets,
            String deliveredKg, String deliveredM3, String deliveredPallets) {
        BigDecimal refusedKg = new BigDecimal(attemptedKg).subtract(new BigDecimal(deliveredKg));
        BigDecimal refusedM3 = new BigDecimal(attemptedM3).subtract(new BigDecimal(deliveredM3));
        BigDecimal refusedPallets = new BigDecimal(attemptedPallets).subtract(new BigDecimal(deliveredPallets));
        return "{\"attemptedWeightKg\":" + attemptedKg + ",\"attemptedVolumeM3\":" + attemptedM3
                + ",\"attemptedPallets\":" + attemptedPallets + ",\"deliveredWeightKg\":" + deliveredKg
                + ",\"deliveredVolumeM3\":" + deliveredM3 + ",\"deliveredPallets\":" + deliveredPallets
                + ",\"refusedWeightKg\":" + refusedKg + ",\"refusedVolumeM3\":" + refusedM3
                + ",\"refusedPallets\":" + refusedPallets + "}";
    }

    private void assertEligible(String orderId, LocalDate date, boolean expected) throws Exception {
        String number = queryString("SELECT order_number FROM tms.transport_order WHERE id = '" + orderId + "'");
        String body = mockMvc.perform(asAdmin(get(PLANNING + "/eligible-orders"))
                        .param("orderNumber", number)
                        .param("serviceDate", date.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.content[*].id");
        assertThat(ids.contains(orderId)).as("order %s in the eligible pool", number).isEqualTo(expected);
    }

    @SafeVarargs
    private static List<Integer> concurrently(Callable<Integer>... calls) throws Exception {
        CyclicBarrier together = new CyclicBarrier(calls.length);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Callable<Integer>> started = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                started.add(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return call.call();
                });
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(started)) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions stopAction(String tripId, String stopId, String action) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/stops/" + stopId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
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

    private void confirmRun(String runId) throws Exception {
        confirm(runId).andExpect(status().isOk());
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
