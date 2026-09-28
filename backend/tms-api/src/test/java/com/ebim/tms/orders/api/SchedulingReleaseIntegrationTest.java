package com.ebim.tms.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
 * ADR-014 end to end: release gated on eligibility, bulk release, holds, the planning-candidate
 * rule, route frequency in automatic planning, and a hold on a committed trip - through the real
 * security chain against a freshly migrated PostgreSQL (V54 included).
 *
 * <p>The fixture: origin {@code SR-CD} has routes - {@code R-1} stops at {@code ST-1}, {@code R-A}
 * and {@code R-B} both stop at {@code ST-2} (ambiguous), {@code R-FREQ} stops at {@code ST-4} and runs
 * on a frequency that never runs. {@code ST-3} is on no route of {@code SR-CD}. Origin {@code SR-NR}
 * has no route at all. {@code ST-5}'s own calendar never runs; {@code ST-6} runs every day with a
 * 10:00 cutoff and a 5-day lead time.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SchedulingReleaseIntegrationTest.JwtDecoderOverride.class)
class SchedulingReleaseIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String PLANNING = "/api/v1/planning";
    private static final String TRIPS = PLANNING + "/trips";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final UUID ORGANIZATION = UUID.fromString("5c5c5c5c-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("5c5c5c5c-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("5c5c5c5c-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("5c5c5c5c-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("5c5c5c5c-0000-4000-8000-0000000000e2");
    private static final UUID OTHER_AUTH = UUID.fromString("5c5c5c5c-0000-4000-8000-0000000000e3");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String origin;
    private static String originWithoutRoutes;
    private static String onRoute;
    private static String ambiguous;
    private static String offRoute;
    private static String idleRouteStop;
    private static String idleCalendar;
    private static String tightCalendar;
    private static String carrier;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String viewerToken;
    private String otherToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_scheduling_release");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'SR-ORG', 'Scheduling Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'SR-A', 'Scheduling A', 'America/Lima'),
                    ('%s', '%s', 'SR-B', 'Scheduling B', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'sr.admin@example.invalid', 'SR Admin'),
                    ('%s', 'sr.viewer@example.invalid', 'SR Viewer'),
                    ('%s', 'sr.other@example.invalid', 'SR Other');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH,
                VIEWER_AUTH, OTHER_AUTH));
        membership("sr.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("sr.viewer@example.invalid", COMPANY_A, "VIEWER");
        membership("sr.other@example.invalid", COMPANY_B, "COMPANY_ADMIN");

        origin = insertLocation("SR-CD", "ORIGIN");
        originWithoutRoutes = insertLocation("SR-NR", "ORIGIN");
        onRoute = insertLocation("ST-1", "DESTINATION");
        ambiguous = insertLocation("ST-2", "DESTINATION");
        offRoute = insertLocation("ST-3", "DESTINATION");
        idleRouteStop = insertLocation("ST-4", "DESTINATION");
        idleCalendar = insertLocation("ST-5", "DESTINATION");
        tightCalendar = insertLocation("ST-6", "DESTINATION");

        String never = insertReturningId("INSERT INTO tms.frequency (company_id, code, name) VALUES ('" + COMPANY_A
                + "', 'FR-NEVER', 'Never')");
        String always = insertReturningId("INSERT INTO tms.frequency (company_id, code, name) VALUES ('" + COMPANY_A
                + "', 'FR-DAILY', 'Daily')");
        for (int day = 1; day <= 7; day++) {
            execute("INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week, enabled, cutoff_time,"
                    + " lead_time_days) VALUES ('" + always + "', " + day + ", true, '10:00', 5)");
        }
        execute("INSERT INTO tms.location_frequency (company_id, location_id, frequency_id) VALUES ('" + COMPANY_A
                + "', '" + idleCalendar + "', '" + never + "')");
        execute("INSERT INTO tms.location_frequency (company_id, location_id, frequency_id) VALUES ('" + COMPANY_A
                + "', '" + tightCalendar + "', '" + always + "')");

        route("R-1", null, onRoute);
        route("R-A", null, ambiguous);
        route("R-B", null, ambiguous);
        route("R-FREQ", never, idleRouteStop);

        carrier = insertReturningId("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type,"
                + " tax_id_value) VALUES ('" + COMPANY_A + "', 'SR-CARR', 'SR Carrier', 'RUC', '20100000019')");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
        otherToken = TestJwts.validFor(OTHER_AUTH);
    }

    // --- single release ------------------------------------------------------------------


    @Test
    @DisplayName("ELIGIBLE releases with no body, as before, and is audited ORDER_RELEASED")
    void eligibleReleasesWithoutBody() throws Exception {
        String order = order(origin, onRoute, farDate());

        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/mark-ready")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));

        assertThat(queryString("SELECT metadata FROM tms.audit_event WHERE aggregate_id = '" + order
                + "' AND action = 'ORDER_RELEASED'")).contains("\"eligibility\":\"ELIGIBLE\"");
    }

    @Test
    @DisplayName("an origin with no route at all is NOT_CONFIGURED: informative, released without a reason")
    void notConfiguredReleasesWithoutReason() throws Exception {
        String order = order(originWithoutRoutes, onRoute, farDate());

        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/mark-ready")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));
        assertThat(queryString("SELECT metadata FROM tms.audit_event WHERE aggregate_id = '" + order
                + "' AND action = 'ORDER_RELEASED'")).contains("ROUTE_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("BLOCKED is 409 with the reasons in the problem detail, and nothing changes")
    void blockedIsRefusedWithReasons() throws Exception {
        String notFound = order(origin, offRoute, farDate());
        String ambiguity = order(origin, ambiguous, farDate());

        release(notFound, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.eligibility").value("BLOCKED"))
                .andExpect(jsonPath("$.overrideRequired").value(false))
                .andExpect(jsonPath("$.reasons[0].code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.reasons[0].severity").value("BLOCKED"));
        // Even a reason does not release a blocked order.
        release(ambiguity, "the planner knows best")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0].code").value("ROUTE_AMBIGUOUS"))
                .andExpect(jsonPath("$.reasons[0].detail").value(containsString("R-A")));

        assertThat(orderStatus(notFound)).isEqualTo("NOT_READY");
        assertThat(orderStatus(ambiguity)).isEqualTo("NOT_READY");
    }

    @Test
    @DisplayName("MISSING_CAPACITY keeps today's rule: blocked only when weight, volume and pallets are all zero")
    void missingCapacity() throws Exception {
        String empty = order(origin, onRoute, farDate(), "0", "0", "0");
        String palletsOnly = order(origin, onRoute, farDate(), "0", "0", "2");

        release(empty, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0].code").value("MISSING_CAPACITY"));
        release(palletsOnly, null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FREQUENCY_OVERRIDE needs an override reason; with one it releases and the reason is audited")
    void frequencyOverrideNeedsAReason() throws Exception {
        String order = order(originWithoutRoutes, idleCalendar, farDate());

        release(order, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.eligibility").value("WARNING"))
                .andExpect(jsonPath("$.overrideRequired").value(true))
                .andExpect(jsonPath("$.reasons[*].code").value(hasItem("FREQUENCY_OVERRIDE")));
        release(order, "   ").andExpect(status().isConflict());
        release(order, "x".repeat(501)).andExpect(status().isBadRequest());

        release(order, "customer accepted a Saturday drop").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));
        String audit = queryString("SELECT metadata FROM tms.audit_event WHERE aggregate_id = '" + order
                + "' AND action = 'ORDER_RELEASED'");
        assertThat(audit).contains("FREQUENCY_OVERRIDE", "customer accepted a Saturday drop", "WARNING");
    }

    @Test
    @DisplayName("CUTOFF_MISSED: past (dispatch - lead time) at cutoff in the company zone needs a reason")
    void cutoffMissed() throws Exception {
        // Dispatch tomorrow with a 5-day lead time: the window closed four days ago.
        String late = order(originWithoutRoutes, tightCalendar, LocalDate.now(LIMA).plusDays(1));
        String inTime = order(originWithoutRoutes, tightCalendar, farDate());

        release(late, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.overrideRequired").value(true))
                .andExpect(jsonPath("$.reasons[*].code").value(hasItem("CUTOFF_MISSED")));
        release(late, "urgent replacement stock").andExpect(status().isOk());
        release(inTime, null).andExpect(status().isOk());
    }

    // --- bulk release --------------------------------------------------------------------


    @Test
    @DisplayName("200 when every item was released")
    void allReleased() throws Exception {
        String first = order(origin, onRoute, farDate());
        String second = order(originWithoutRoutes, onRoute, farDate());

        bulk(null, first, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(2))
                .andExpect(jsonPath("$.released").value(2))
                .andExpect(jsonPath("$.refused").value(0));
    }

    @Test
    @DisplayName("207 with one result per item when any was refused; released items stay released")
    void partial() throws Exception {
        String eligible = order(origin, onRoute, farDate());
        String blocked = order(origin, offRoute, farDate());
        String needsReason = order(originWithoutRoutes, idleCalendar, farDate());

        bulk(null, eligible, blocked, needsReason)
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.released").value(1))
                .andExpect(jsonPath("$.refused").value(2))
                .andExpect(jsonPath("$.results[0].released").value(true))
                .andExpect(jsonPath("$.results[1].released").value(false))
                .andExpect(jsonPath("$.results[1].eligibility").value("BLOCKED"))
                .andExpect(jsonPath("$.results[1].reasons[0].code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.results[2].overrideRequired").value(true));
        assertThat(orderStatus(eligible)).isEqualTo("READY_FOR_PLANNING");

        // One reason for the batch covers every item that needs one; the blocked one stays blocked.
        bulk("approved by commercial", blocked, needsReason)
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.results[0].released").value(false))
                .andExpect(jsonPath("$.results[1].released").value(true));
    }

    @Test
    @DisplayName("an unknown or foreign order is a refused item, not a failed batch")
    void foreignOrderIsARefusedItem() throws Exception {
        String eligible = order(origin, onRoute, farDate());

        bulk(null, eligible, UUID.randomUUID().toString())
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.results[1].message").value("Order not found."));
    }

    @Test
    @DisplayName("a read-only role may not release")
    void viewerMayNotRelease() throws Exception {
        mockMvc.perform(asViewer(post(ORDERS + "/release")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[\"" + order(origin, onRoute, farDate()) + "\"]}"))
                .andExpect(status().isForbidden());
    }

    // --- holds ---------------------------------------------------------------------------


    @Test
    @DisplayName("an active blocking hold blocks release; lifting it with a reason releases; both are audited")
    void holdBlocksReleaseUntilLifted() throws Exception {
        String order = order(origin, onRoute, farDate());
        String hold = placeHold(order, "COMMERCIAL", true);

        mockMvc.perform(asAdmin(get(ORDERS + "/" + order + "/holds")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(hold))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].source").value("OPERATOR"));
        release(order, "override attempt")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0].code").value("ACTIVE_BLOCKING_HOLD"));

        liftHold(order, hold, "credit approved").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.releaseReason").value("credit approved"));
        liftHold(order, hold, "again").andExpect(status().isConflict());
        release(order, null).andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + order
                + "' AND action IN ('ORDER_HOLD_PLACED', 'ORDER_HOLD_RELEASED')")).isEqualTo(2);
    }

    @Test
    @DisplayName("a non-blocking hold is a note: it stops nothing")
    void nonBlockingHoldStopsNothing() throws Exception {
        String order = order(origin, onRoute, farDate());
        placeHold(order, "ADDRESS", false);

        release(order, null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("placing a hold validates the request and needs orders.hold:manage")
    void validationAndPermission() throws Exception {
        String order = order(origin, onRoute, farDate());

        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/holds")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdType\":\"COMMERCIAL\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asAdmin(post(ORDERS + "/" + order + "/holds")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdType\":\"COMMERCIAL\",\"reasonCode\":\"lower case\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asViewer(post(ORDERS + "/" + order + "/holds")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdType\":\"COMMERCIAL\",\"reason\":\"credit\"}"))
                .andExpect(status().isForbidden());
        // Reading needs only orders.order:read.
        mockMvc.perform(asViewer(get(ORDERS + "/" + order + "/holds"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("another company can neither see nor touch a hold, and RLS hides the row from its tenant")
    void tenantIsolation() throws Exception {
        String order = order(origin, onRoute, farDate());
        String hold = placeHold(order, "INVENTORY", true);

        mockMvc.perform(asOther(get(ORDERS + "/" + order + "/holds"))).andExpect(status().isNotFound());
        mockMvc.perform(asOther(post(ORDERS + "/" + order + "/holds")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdType\":\"MANUAL\",\"reason\":\"not mine\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(asOther(post(ORDERS + "/" + order + "/holds/" + hold + "/release"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"releaseReason\":\"not mine\"}"))
                .andExpect(status().isNotFound());

        assertThat(countAsTenant(COMPANY_B, "SELECT count(*) FROM tms.order_hold WHERE id = '" + hold + "'"))
                .isZero();
        assertThat(countAsTenant(COMPANY_A, "SELECT count(*) FROM tms.order_hold WHERE id = '" + hold + "'"))
                .isEqualTo(1);
    }

    // --- the board -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /orders/scheduling shows derived eligibility and route per row; derived filters and the summary agree")
    void schedulingBoard() throws Exception {
        LocalDate date = farDate();
        String eligible = order(origin, onRoute, date);
        String blocked = order(origin, offRoute, date);
        String held = order(origin, onRoute, date);
        placeHold(held, "CUSTOMER", true);

        String body = mockMvc.perform(asAdmin(get(ORDERS + "/scheduling"))
                        .param("originId", origin)
                        .param("serviceDateFrom", date.toString())
                        .param("serviceDateTo", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(field(body, eligible, "routeCode")).isEqualTo("R-1");
        assertThat(field(body, eligible, "eligibility")).isEqualTo("ELIGIBLE");
        assertThat(field(body, blocked, "routeResolution")).isEqualTo("NOT_FOUND");
        assertThat(field(body, held, "activeBlockingHolds")).isEqualTo(1);

        mockMvc.perform(asAdmin(get(ORDERS + "/scheduling"))
                        .param("originId", origin)
                        .param("serviceDateFrom", date.toString())
                        .param("serviceDateTo", date.toString())
                        .param("eligibility", "BLOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(asAdmin(get(ORDERS + "/scheduling"))
                        .param("serviceDateFrom", date.toString())
                        .param("serviceDateTo", date.toString())
                        .param("hasHold", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(asAdmin(get(ORDERS + "/scheduling"))
                        .param("serviceDateFrom", date.toString())
                        .param("serviceDateTo", date.toString())
                        .param("routeCode", "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(asAdmin(get(ORDERS + "/scheduling/summary"))
                        .param("serviceDateFrom", date.toString())
                        .param("serviceDateTo", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total").value(3))
                .andExpect(jsonPath("$.totals.eligible").value(1))
                .andExpect(jsonPath("$.totals.blocked").value(2))
                .andExpect(jsonPath("$.totals.withHolds").value(1))
                .andExpect(jsonPath("$.groups.length()").value(2));

        mockMvc.perform(asAdmin(get(ORDERS + "/" + blocked + "/scheduling")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons[0].code").value("ROUTE_NOT_FOUND"));
        mockMvc.perform(asOther(get(ORDERS + "/" + blocked + "/scheduling"))).andExpect(status().isNotFound());
    }

    // --- planning candidates -------------------------------------------------------------


    @Test
    @DisplayName("a released order with an active blocking hold leaves the eligible pool and cannot be assigned by hand")
    void heldOrderIsNoCandidate() throws Exception {
        LocalDate date = farDate();
        String order = releasedOrder(origin, onRoute, date);
        String hold = placeHold(order, "INVENTORY", true);

        assertEligible(order, date, false);
        String run = newRun(date);
        String trip = newTrip(run, date);
        assign(trip, order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("active blocking hold")));

        liftHold(order, hold, "stock arrived").andExpect(status().isOk());
        assertEligible(order, date, true);
        assign(trip, order).andExpect(status().isOk());
    }

    @Test
    @DisplayName("automatic planning skips a held order and an order whose route frequency does not run; a person may still assign the latter")
    void autoPlanRespectsHoldsAndRouteFrequency() throws Exception {
        LocalDate date = farDate();
        String held = releasedOrder(origin, onRoute, date);
        placeHold(held, "COMMERCIAL", true);
        String idle = order(origin, idleRouteStop, date);
        release(idle, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[*].code").value(hasItem("FREQUENCY_OVERRIDE")));
        release(idle, "extra run agreed with the customer").andExpect(status().isOk());

        String run = newRun(date);
        String preview = mockMvc.perform(asAdmin(get(PLANNING + "/runs/" + run + "/auto-plan/preview")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> reasons = JsonPath.read(preview, "$.unplanned[?(@.orderId=='" + idle + "')].reason");
        assertThat(reasons).containsExactly("NOT_SERVICEABLE_ON_DATE");
        List<String> considered = JsonPath.read(preview, "$.unplanned[*].orderId");
        assertThat(considered).doesNotContain(held);
        assertThat(preview).doesNotContain(held);

        String trip = newTrip(run, date);
        assign(trip, idle).andExpect(status().isOk());
    }

    // --- committed trips -----------------------------------------------------------------

    @Test
    @DisplayName("a hold on a planned order unplans nothing: it blocks dispatch and raises ORDER_HOLD_ON_COMMITTED_TRIP")
    void holdOnACommittedTrip() throws Exception {
        LocalDate date = farDate();
        String order = releasedOrder(origin, onRoute, date);
        String run = newRun(date);
        String trip = newTrip(run, date);
        assign(trip, order).andExpect(status().isOk());
        mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/confirm")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        transition(trip, "ready").andExpect(status().isOk());

        String hold = placeHold(order, "CUSTOMER", true);
        assertThat(orderStatus(order)).isEqualTo("PLANNED");
        String number = queryString("SELECT order_number FROM tms.transport_order WHERE id = '" + order + "'");

        transition(trip, "dispatch")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString(number)))
                .andExpect(jsonPath("$.detail").value(containsString("active blocking hold")));
        mockMvc.perform(asAdmin(get("/api/v1/monitoring/control-tower")).param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories[?(@.type=='ORDER_HOLD_ON_COMMITTED_TRIP')].sourceId").value(hasItem(order)))
                .andExpect(jsonPath("$.advisories[?(@.type=='ORDER_HOLD_ON_COMMITTED_TRIP')].tripId").value(hasItem(trip)));

        liftHold(order, hold, "customer confirmed").andExpect(status().isOk());
        mockMvc.perform(asAdmin(get("/api/v1/monitoring/control-tower")).param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories[*].type").value(not(hasItem("ORDER_HOLD_ON_COMMITTED_TRIP"))));
        transition(trip, "dispatch").andExpect(status().isOk());
    }

    // --- helpers -------------------------------------------------------------------------

    private ResultActions release(String orderId, String overrideReason) throws Exception {
        MockHttpServletRequestBuilder request = asAdmin(post(ORDERS + "/" + orderId + "/mark-ready"));
        if (overrideReason != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"overrideReason\":\"" + overrideReason + "\"}");
        }
        return mockMvc.perform(request);
    }

    /** One field of the board row for {@code orderId}. */
    private static Object field(String body, String orderId, String field) {
        List<Object> values = JsonPath.read(body, "$.content[?(@.orderId=='" + orderId + "')]." + field);
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private ResultActions bulk(String overrideReason, String... orderIds) throws Exception {
        String ids = String.join("\",\"", orderIds);
        String reason = overrideReason == null ? "" : ",\"overrideReason\":\"" + overrideReason + "\"";
        return mockMvc.perform(asAdmin(post(ORDERS + "/release")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderIds\":[\"" + ids + "\"]" + reason + "}"));
    }

    private String releasedOrder(String originId, String destinationId, LocalDate date) throws Exception {
        String order = order(originId, destinationId, date);
        release(order, null).andExpect(status().isOk());
        return order;
    }

    private String placeHold(String orderId, String type, boolean blocking) throws Exception {
        String body = mockMvc.perform(asAdmin(post(ORDERS + "/" + orderId + "/holds"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdType\":\"" + type + "\",\"reasonCode\":\"TEST_HOLD\",\"reason\":\"held in a test\","
                                + "\"blocking\":" + blocking + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocking").value(blocking))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions liftHold(String orderId, String holdId, String reason) throws Exception {
        return mockMvc.perform(asAdmin(post(ORDERS + "/" + orderId + "/holds/" + holdId + "/release"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"releaseReason\":\"" + reason + "\"}"));
    }

    private void assertEligible(String orderId, LocalDate date, boolean expected) throws Exception {
        String body = mockMvc.perform(asAdmin(get(PLANNING + "/eligible-orders"))
                        .param("serviceDate", date.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.content[*].id");
        assertThat(ids.contains(orderId)).as("order in the eligible pool").isEqualTo(expected);
    }

    private ResultActions assign(String tripId, String orderId) throws Exception {
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/assignments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\"}"));
    }

    private ResultActions transition(String tripId, String action) throws Exception {
        String body = mockMvc.perform(asAdmin(get(TRIPS + "/" + tripId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(body, "$.trip.version");
        return mockMvc.perform(asAdmin(post(TRIPS + "/" + tripId + "/" + action))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version.longValue() + "}"));
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
                .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString());
    }

    private MockHttpServletRequestBuilder asOther(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + otherToken)
                .header(ApiHeaders.COMPANY_ID, COMPANY_B.toString());
    }

    /** A dispatch date of its own per test, far enough ahead that no seeded deadline has passed. */
    private static LocalDate farDate() {
        return LocalDate.now(LIMA).plusDays(60 + SEQUENCE.incrementAndGet());
    }

    private static String order(String originId, String destinationId, LocalDate serviceDate) {
        return order(originId, destinationId, serviceDate, "1000", "10", "10");
    }

    private static String order(String originId, String destinationId, LocalDate serviceDate, String weight,
            String volume, String pallets) {
        return insertReturningId("INSERT INTO tms.transport_order (company_id, order_number, origin_id,"
                + " destination_id, service_date, status, total_weight_kg, total_volume_m3, total_pallets) VALUES ('"
                + COMPANY_A + "', 'TO-SR-" + String.format(Locale.ROOT, "%06d", SEQUENCE.incrementAndGet()) + "', '"
                + originId + "', '" + destinationId + "', '" + serviceDate + "', 'NOT_READY', " + weight + ", "
                + volume + ", " + pallets + ")");
    }

    private static String vehicle() {
        int number = SEQUENCE.incrementAndGet();
        String type = insertReturningId("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + COMPANY_A + "', 'SR-TYPE-" + number
                + "', 'SR type', 100000, 400, 1000)");
        return insertReturningId("INSERT INTO tms.vehicle (company_id, code, license_plate, carrier_id,"
                + " vehicle_type_id) VALUES ('" + COMPANY_A + "', 'SR-VEH-" + number + "', '"
                + String.format(Locale.ROOT, "SRV-%05d", number) + "', '" + carrier + "', '" + type + "')");
    }

    private static void route(String code, String frequencyId, String... stops) {
        String route = insertReturningId("INSERT INTO tms.route (company_id, code, name, origin_id, frequency_id)"
                + " VALUES ('" + COMPANY_A + "', '" + code + "', '" + code + "', '" + origin + "', "
                + (frequencyId == null ? "NULL" : "'" + frequencyId + "'") + ")");
        for (int index = 0; index < stops.length; index++) {
            execute("INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence) VALUES ('" + route
                    + "', '" + COMPANY_A + "', '" + stops[index] + "', " + (index + 1) + ")");
        }
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = '%s';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%s'
                JOIN tms.role r ON r.code = '%s'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, companyId, email, email, roleCode, companyId));
    }

    private static String insertLocation(String code, String role) {
        String id = insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY_A
                + "', '" + code + "', '" + code + "')");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + id + "', '" + role + "')");
        return id;
    }

    private static String orderStatus(String orderId) {
        return queryString("SELECT status FROM tms.transport_order WHERE id = '" + orderId + "'");
    }

    private static long count(String sql) {
        return Long.parseLong(queryString(sql));
    }

    /** What {@code TenantScopedDataSource} does for a company-scoped request: the runtime role and the tenant. */
    private static long countAsTenant(UUID companyId, String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('tms.company_id', '" + companyId + "', false)");
            statement.execute("SET ROLE tms_app");
            try (var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read as tenant", failed);
        }
    }

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed a scheduling fixture", failed);
        }
    }

    private static String queryString(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the scheduling fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the scheduling fixture", failed);
        }
    }
}
