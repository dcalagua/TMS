package com.ebim.tms.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.planning.application.TenderWaterfallSweep;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The automatic advance of a tender waterfall against a real PostgreSQL (ITEM 10).
 *
 * <p>The waterfall is started through the API by a planner, exactly as a dispatcher would. Its first
 * offer is then made to have lapsed by moving its two instants into the past - the deadline is a
 * stored instant, so this is the database-side equivalent of a fixed clock, and it keeps
 * {@code TripTenderService} (which reads the system clock) and the waterfall service (which reads the
 * {@code Clock} bean) agreeing on what "now" is. Then two sweeps run at the same instant on two threads,
 * standing in for two nodes.
 *
 * <p>The scheduler itself stays off ({@code application-test.yml}); the sweep is driven by calling it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TenderWaterfallAutoAdvanceIntegrationTest.JwtDecoderOverride.class)
class TenderWaterfallAutoAdvanceIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("99999999-0000-4000-8000-000000000b01");
    private static final UUID COMPANY = UUID.fromString("99999999-0000-4000-8000-000000000bc1");
    private static final UUID PLANNER_AUTH = UUID.fromString("99999999-0000-4000-8000-000000000be1");
    private static final String DAY = "2026-10-15";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String plannerId;
    private static String carrierA;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenderWaterfallSweep sweep;

    private String plannerToken;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_tender_waterfall_auto");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        // Two sweeping threads each hold a connection while the test reads the database: more than the
        // suite's default of two, which is sized for one MockMvc thread.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION + "', 'WF-ORG', 'Waterfall Org')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('" + COMPANY + "', '"
                + ORGANIZATION + "', 'WF-A', 'Waterfall Company', 'America/Lima')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + PLANNER_AUTH
                + "', 'wf.planner@example.invalid', 'WF Planner')");
        plannerId = scalar("SELECT id FROM tms.app_user WHERE email = 'wf.planner@example.invalid'");
        execute("INSERT INTO tms.membership (app_user_id, organization_id, company_id) VALUES ('" + plannerId + "', '"
                + ORGANIZATION + "', '" + COMPANY + "')");
        execute("INSERT INTO tms.membership_role (membership_id, role_id) SELECT m.id, r.id FROM tms.membership m"
                + " JOIN tms.role r ON r.code = 'PLANNER' WHERE m.app_user_id = '" + plannerId + "'");
        carrierA = carrier("WFA");
        carrier("WFB");
        carrier("WFC");
    }

    @BeforeEach
    void mintToken() {
        plannerToken = TestJwts.validFor(PLANNER_AUTH);
    }

    @Test
    @DisplayName("a lapsed offer moves to the next carrier exactly once, even with two sweeps at the same instant")
    void lapsedOfferAdvancesOnceUnderTwoNodes() throws Exception {
        String trip = confirmedTrip();
        startWaterfall(trip);
        lapse(trip);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService nodes = Executors.newFixedThreadPool(2);
        List<Future<TenderWaterfallSweep.Result>> results = new ArrayList<>();
        try {
            for (int node = 0; node < 2; node++) {
                results.add(nodes.submit(() -> {
                    go.await();
                    return sweep.sweepAll();
                }));
            }
            go.countDown();
            int advanced = 0;
            int failed = 0;
            for (Future<TenderWaterfallSweep.Result> result : results) {
                TenderWaterfallSweep.Result value = result.get(60, TimeUnit.SECONDS);
                advanced += value.advanced();
                failed += value.failed();
            }
            assertThat(advanced).isEqualTo(1);
            assertThat(failed).isZero();
        } finally {
            nodes.shutdownNow();
        }

        // One new offer, to rank 2, live; rank 1 lapsed - on the candidate and on the tender row.
        assertThat(count("SELECT count(*) FROM tms.trip_tender WHERE trip_id = '" + trip + "'")).isEqualTo(2);
        assertThat(scalar(candidate(trip, 1, "c.status"))).isEqualTo("EXPIRED");
        assertThat(scalar(candidate(trip, 2, "c.status"))).isEqualTo("OFFERED");
        assertThat(scalar("SELECT status FROM tms.trip_tender WHERE trip_id = '" + trip + "' AND attempt = 1"))
                .isEqualTo("EXPIRED");
        assertThat(scalar("SELECT te.status || '/' || te.carrier_id FROM tms.trip_tender te WHERE te.trip_id = '"
                + trip + "' AND te.attempt = 2")).isEqualTo("SENT/" + scalar(candidate(trip, 2, "c.carrier_id")));
        // Signed with whoever started the waterfall; audited as the job.
        assertThat(scalar("SELECT created_by || '/' || sent_by FROM tms.trip_tender WHERE trip_id = '" + trip
                + "' AND attempt = 2")).isEqualTo(plannerId + "/" + plannerId);
        assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_id = '" + trip
                + "' AND actor_machine_label = '" + TenderWaterfallSweep.ACTOR_LABEL + "'")).isPositive();

        // Idempotent: the new offer is inside its deadline, so the next pass does nothing.
        TenderWaterfallSweep.Result again = sweep.sweepAll();
        assertThat(again.advanced()).isZero();
        assertThat(count("SELECT count(*) FROM tms.trip_tender WHERE trip_id = '" + trip + "'")).isEqualTo(2);
    }

    @Test
    @DisplayName("an offer still inside its deadline is never advanced by the sweep")
    void liveOfferIsLeftAlone() throws Exception {
        String trip = confirmedTrip();
        startWaterfall(trip);

        assertThat(sweep.sweepAll().advanced()).isZero();
        assertThat(count("SELECT count(*) FROM tms.trip_tender WHERE trip_id = '" + trip + "'")).isEqualTo(1);
        assertThat(scalar(candidate(trip, 1, "c.status"))).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("the last candidate lapsing ends the waterfall EXHAUSTED; the card says the advance is off by default")
    void lastLapseExhausts() throws Exception {
        String trip = confirmedTrip();
        mockMvc.perform(as(post("/api/v1/planning/trips/" + trip + "/tenders/waterfall")
                        .param("maxAttempts", "1").param("responseMinutes", "60")))
                .andExpect(status().isOk());
        lapse(trip);

        assertThat(sweep.sweepAll().exhausted()).isEqualTo(1);
        mockMvc.perform(as(get("/api/v1/planning/trips/" + trip + "/tenders/waterfall")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXHAUSTED"))
                .andExpect(jsonPath("$.autoAdvance").value(false));
        assertThat(scalar("SELECT status FROM tms.trip_tender WHERE trip_id = '" + trip + "' AND attempt = 1"))
                .isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------ fixture

    private void startWaterfall(String trip) throws Exception {
        mockMvc.perform(as(post("/api/v1/planning/trips/" + trip + "/tenders/waterfall")
                        .param("responseMinutes", "60")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.candidates[0].status").value("OFFERED"));
    }

    /** Moves the live offer's two instants an hour and two into the past: its deadline has gone. */
    private static void lapse(String trip) {
        execute("UPDATE tms.trip_tender SET sent_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'"
                + " WHERE trip_id = '" + trip + "' AND status = 'SENT'");
    }

    private static String candidate(String trip, int rank, String expression) {
        return "SELECT " + expression + " FROM tms.tender_waterfall_candidate c JOIN tms.tender_waterfall w"
                + " ON w.id = c.waterfall_id WHERE w.trip_id = '" + trip + "' AND c.rank = " + rank;
    }

    private static String carrier(String code) {
        return scalar("INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value) VALUES ('"
                + COMPANY + "', '" + code + "', '" + code + " SA', 'RUC', '20"
                + String.format("%09d", Math.abs(code.hashCode()) % 1_000_000_000) + "') RETURNING id");
    }

    /** A confirmed shipment on carrier A's truck: offerable, and with its own vehicle so they never collide. */
    private static String confirmedTrip() {
        int n = SEQUENCE.incrementAndGet();
        String type = scalar("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3,"
                + " max_pallets) VALUES ('" + COMPANY + "', 'WVT-" + n + "', 'Rigid', 10000, 40, 20) RETURNING id");
        String vehicle = scalar("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate, carrier_id)"
                + " VALUES ('" + COMPANY + "', '" + type + "', 'WV-" + n + "', 'WFP-" + n + "', '" + carrierA
                + "') RETURNING id");
        String origin = scalar("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY + "', 'WO-" + n
                + "', 'Origin') RETURNING id");
        String run = scalar("INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date, status)"
                + " VALUES ('" + COMPANY + "', 'PL-WF-" + n + "', '" + origin + "', '" + DAY + "', 'DRAFT') RETURNING id");
        return scalar("INSERT INTO tms.trip (company_id, planning_run_id, planning_date, trip_number, carrier_id,"
                + " vehicle_id, status, planned_departure_at, confirmed_at, snapshot_max_weight_kg,"
                + " snapshot_max_volume_m3, snapshot_max_pallets, capacity_snapshot_at, confirmed_by) VALUES ('"
                + COMPANY + "', '" + run + "', '" + DAY + "', 1, '" + carrierA + "', '" + vehicle + "', 'CONFIRMED',"
                + " now() + interval '2 days', now(), 10000, 40, 20, now(), '" + plannerId + "') RETURNING id");
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + plannerToken)
                .header(ApiHeaders.COMPANY_ID, COMPANY.toString());
    }

    private static long count(String sql) {
        return Long.parseLong(scalar(sql));
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
            throw new IllegalStateException("could not read the waterfall fixture: " + sql, failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the waterfall fixture: " + sql, failed);
        }
    }
}
