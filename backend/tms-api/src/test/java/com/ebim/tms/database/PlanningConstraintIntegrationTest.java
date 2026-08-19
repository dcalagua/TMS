package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the V11 planning constraints hold at the database level, independent of the Java rules in
 * {@code TripService}/{@code PlanningRunService} - the same defense-in-depth proof
 * {@link OrderConstraintIntegrationTest} gives V10's tables.
 *
 * <p>The test that matters most is {@link #concurrentAssignmentOfTheSameOrderBlocksThenFails}: it
 * is the only place the "two planners, one order, one instant" invariant is proven against real
 * concurrent transactions rather than against application code that could be refactored around.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class PlanningConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_planning_constraints");
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = PostgresTestDatabase.connect(jdbcUrl);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    // --- the assignment invariant -------------------------------------------------

    @Test
    @DisplayName("an order has at most one open whole-order assignment, across every trip")
    void oneOpenWholeOrderAssignmentPerOrder() throws SQLException {
        Fixture fixture = fixture("ONE-OPEN");
        UUID tripOne = insertTrip(fixture, 1);
        UUID tripTwo = insertTrip(fixture, 2);
        UUID order = insertOrder(fixture, "TO-ONEOPEN-1");

        insertAssignment(fixture, tripOne, order);
        assertViolates(UNIQUE_VIOLATION, () -> insertAssignment(fixture, tripTwo, order));
    }

    @Test
    @DisplayName("a closed assignment keeps its history and frees the order for a new one")
    void closedAssignmentFreesTheOrder() throws SQLException {
        Fixture fixture = fixture("REASSIGN");
        UUID tripOne = insertTrip(fixture, 1);
        UUID tripTwo = insertTrip(fixture, 2);
        UUID order = insertOrder(fixture, "TO-REASSIGN-1");

        UUID first = insertAssignment(fixture, tripOne, order);
        execute("UPDATE tms.trip_order_assignment SET status = 'REMOVED', removed_at = now(),"
                + " removal_reason = 'moved' WHERE id = '" + first + "'");
        insertAssignment(fixture, tripTwo, order);

        assertThat(count("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + order + "'"))
                .as("both the closed and the open assignment survive: history is never overwritten")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + order
                + "' AND status = 'ACTIVE'")).isOne();
    }

    @Test
    @DisplayName("the open-assignment index is partial, so a future split allocation is not blocked by it")
    void splitAllocationsAreNotBlocked() throws SQLException {
        // V1 never writes whole_order = false. This test exists to prove the invariant that guards
        // V1 does not have to be dropped the day partial assignment arrives - see the index comment
        // in V11 and docs/domain/PLANNING_MANUAL_V1.md, "Why an assignment aggregate".
        Fixture fixture = fixture("SPLIT");
        UUID tripOne = insertTrip(fixture, 1);
        UUID tripTwo = insertTrip(fixture, 2);
        UUID order = insertOrder(fixture, "TO-SPLIT-1");

        insertAssignment(fixture, tripOne, order, false, 100);
        insertAssignment(fixture, tripTwo, order, false, 50);

        assertThat(count("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + order
                + "' AND status = 'ACTIVE'")).isEqualTo(2);
    }

    @Test
    @DisplayName("two concurrent transactions assigning the same order: the second blocks, then fails")
    void concurrentAssignmentOfTheSameOrderBlocksThenFails() throws Exception {
        // Committed on purpose (unlike every rolled-back test above): a second connection cannot
        // see uncommitted fixture rows. The database is dedicated to this class, so the leftovers
        // are harmless - the same reasoning OrderConstraintIntegrationTest documents for its two
        // committing tests.
        Fixture fixture = fixture("CONCURRENT");
        UUID tripOne = insertTrip(fixture, 1);
        UUID tripTwo = insertTrip(fixture, 2);
        UUID order = insertOrder(fixture, "TO-CONCURRENT-1");
        connection.commit();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection second = PostgresTestDatabase.connect(jdbcUrl)) {
            second.setAutoCommit(false);
            insertAssignment(fixture, tripOne, order); // first planner, not committed yet

            Future<Throwable> blocked = executor.submit(() -> catchThrowable(() -> {
                try (Statement statement = second.createStatement()) {
                    statement.execute(assignmentInsert(fixture, tripTwo, order, true, 100));
                }
            }));

            assertThat(catchThrowable(() -> blocked.get(750, TimeUnit.MILLISECONDS)))
                    .as("the second planner must be made to wait, not allowed to insert a competing assignment")
                    .isInstanceOf(TimeoutException.class);

            connection.commit(); // the first planner wins

            Throwable thrown = blocked.get(10, TimeUnit.SECONDS);
            assertThat(thrown).as("the second insert must fail once the first is committed").isNotNull();
            assertThat(thrown).isInstanceOf(SQLException.class);
            assertThat(((SQLException) thrown).getSQLState()).isEqualTo(UNIQUE_VIOLATION);
            second.rollback();
        } finally {
            executor.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM tms.trip_order_assignment WHERE order_id = '" + order
                + "' AND status = 'ACTIVE'")).isOne();
    }

    @Test
    @DisplayName("an assignment's trip and order must belong to its own company")
    void assignmentIsTenantPinned() throws SQLException {
        Fixture companyA = fixture("TENANT-A");
        Fixture companyB = fixture("TENANT-B");
        UUID tripA = insertTrip(companyA, 1);
        UUID orderB = insertOrder(companyB, "TO-TENANT-B1");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertAssignment(companyA, tripA, orderB));
    }

    @Test
    @DisplayName("a removed assignment must carry when it was removed, and an active one must not")
    void removalStampIsCoherent() throws SQLException {
        Fixture fixture = fixture("REMOVAL");
        UUID trip = insertTrip(fixture, 1);
        UUID order = insertOrder(fixture, "TO-REMOVAL-1");
        UUID assignment = insertAssignment(fixture, trip, order);

        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.trip_order_assignment SET status = 'REMOVED'"
                + " WHERE id = '" + assignment + "'"));
        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.trip_order_assignment SET removed_at = now()"
                + " WHERE id = '" + assignment + "'"));
    }

    // --- trips --------------------------------------------------------------------

    @Test
    @DisplayName("a confirmed trip must carry a vehicle, a departure and its frozen capacity")
    void confirmedTripIsComplete() throws SQLException {
        Fixture fixture = fixture("CONFIRM");
        UUID trip = insertTrip(fixture, 1);

        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.trip SET status = 'CONFIRMED',"
                + " confirmed_at = now() WHERE id = '" + trip + "'"));

        execute("UPDATE tms.trip SET vehicle_id = '" + fixture.vehicleId + "',"
                + " planned_departure_at = now() WHERE id = '" + trip + "'");
        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.trip SET status = 'CONFIRMED',"
                + " confirmed_at = now() WHERE id = '" + trip + "'"));

        execute("UPDATE tms.trip SET status = 'CONFIRMED', confirmed_at = now(), snapshot_max_weight_kg = 1000,"
                + " snapshot_max_volume_m3 = 10, snapshot_max_pallets = 4, capacity_snapshot_at = now()"
                + " WHERE id = '" + trip + "'");
        assertThat(count("SELECT count(*) FROM tms.trip WHERE id = '" + trip + "' AND status = 'CONFIRMED'")).isOne();
    }

    @Test
    @DisplayName("a draft trip may not carry a capacity snapshot: live and frozen are never ambiguous")
    void draftTripCarriesNoSnapshot() throws SQLException {
        Fixture fixture = fixture("DRAFTSNAP");
        UUID trip = insertTrip(fixture, 1);

        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.trip SET snapshot_max_weight_kg = 1000,"
                + " capacity_snapshot_at = now() WHERE id = '" + trip + "'"));
    }

    @Test
    @DisplayName("a trip's vehicle must belong to the trip's own company")
    void tripVehicleIsTenantPinned() throws SQLException {
        Fixture companyA = fixture("VEH-A");
        Fixture companyB = fixture("VEH-B");
        UUID tripA = insertTrip(companyA, 1);

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("UPDATE tms.trip SET vehicle_id = '" + companyB.vehicleId
                + "' WHERE id = '" + tripA + "'"));
    }

    @Test
    @DisplayName("a trip number is unique inside its run")
    void tripNumberIsUniquePerRun() throws SQLException {
        Fixture fixture = fixture("TRIPNO");
        insertTrip(fixture, 1);

        assertViolates(UNIQUE_VIOLATION, () -> insertTrip(fixture, 1));
    }

    // --- planning runs ------------------------------------------------------------

    @Test
    @DisplayName("only one open draft run may exist per company, origin and planning date")
    void oneOpenRunPerScope() throws SQLException {
        Fixture fixture = fixture("OPENRUN");

        assertViolates(UNIQUE_VIOLATION, () -> insertRun(fixture, "PL-OPENRUN-2", "DRAFT"));

        // ...but confirming or cancelling the first one frees the scope for a re-plan.
        execute("UPDATE tms.planning_run SET status = 'CANCELLED', cancelled_at = now() WHERE id = '"
                + fixture.runId + "'");
        insertRun(fixture, "PL-OPENRUN-3", "DRAFT");
    }

    @Test
    @DisplayName("a plan number is unique installation-wide, like an order number")
    void planNumberIsGloballyUnique() throws SQLException {
        Fixture companyA = fixture("PLNUM-A");
        Fixture companyB = fixture("PLNUM-B");

        execute("UPDATE tms.planning_run SET status = 'CONFIRMED', confirmed_at = now() WHERE id = '"
                + companyA.runId + "'");
        assertViolates(UNIQUE_VIOLATION,
                () -> insertRunOn(companyB, "PL-PLNUM-A-1", "DRAFT", companyB.originId, "2026-04-02"));
    }

    @Test
    @DisplayName("a run's origin must belong to the run's own company")
    void runOriginIsTenantPinned() throws SQLException {
        Fixture companyA = fixture("RUNORIGIN-A");
        Fixture companyB = fixture("RUNORIGIN-B");

        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> insertRunOn(companyA, "PL-RUNORIGIN-X", "DRAFT", companyB.originId, "2026-04-05"));
    }

    // --- trip stops ---------------------------------------------------------------

    @Test
    @DisplayName("a stop's destination must belong to the trip's own company")
    void stopDestinationIsTenantPinned() throws SQLException {
        Fixture companyA = fixture("STOP-A");
        Fixture companyB = fixture("STOP-B");
        UUID trip = insertTrip(companyA, 1);

        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertStop(companyA, trip, companyB.destinationId, 1));
    }

    @Test
    @DisplayName("a destination appears at most once per trip")
    void oneStopPerDestination() throws SQLException {
        Fixture fixture = fixture("STOPDUP");
        UUID trip = insertTrip(fixture, 1);
        insertStop(fixture, trip, fixture.destinationId, 1);
        insertStop(fixture, trip, fixture.destinationId, 2);

        Throwable thrown = catchThrowable(() -> connection.commit());
        assertThat(thrown).as("a duplicated destination is deferred to commit, but still refused").isNotNull();
        assertThat(((SQLException) thrown).getSQLState()).isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("reordering stops in place survives commit despite the transient duplicate position")
    void reorderingStopsInPlaceSurvivesCommit() throws SQLException {
        // Exactly what Trip.reorderStops does: swap two positions with plain UPDATEs, which
        // necessarily passes through a duplicate (trip_id, sequence) mid-transaction. Deferring
        // uq_trip_stop_trip_sequence to COMMIT is what makes that legal - the same idiom
        // uq_route_stop_route_sequence (V8) uses.
        Fixture fixture = fixture("REORDER");
        UUID trip = insertTrip(fixture, 1);
        UUID first = insertStop(fixture, trip, fixture.destinationId, 1);
        UUID second = insertStop(fixture, trip, fixture.secondDestinationId, 2);

        execute("UPDATE tms.trip_stop SET sequence = 2 WHERE id = '" + first + "'"); // transient duplicate
        execute("UPDATE tms.trip_stop SET sequence = 1 WHERE id = '" + second + "'");
        connection.commit();

        assertThat(count("SELECT count(*) FROM tms.trip_stop WHERE id = '" + second + "' AND sequence = 1")).isOne();
    }

    @Test
    @DisplayName("stops are deleted when their trip is deleted, and a service window must be a real window")
    void stopCascadeAndWindow() throws SQLException {
        Fixture fixture = fixture("STOPWINDOW");
        UUID trip = insertTrip(fixture, 1);

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.trip_stop"
                + " (trip_id, company_id, destination_id, sequence, service_window_start)"
                + " VALUES ('" + trip + "', '" + fixture.companyId + "', '" + fixture.destinationId + "', 1, '08:00')"));

        insertStop(fixture, trip, fixture.destinationId, 1);
        execute("DELETE FROM tms.trip WHERE id = '" + trip + "'");
        assertThat(count("SELECT count(*) FROM tms.trip_stop WHERE trip_id = '" + trip + "'")).isZero();
    }

    // --- helpers -----------------------------------------------------------------

    /** One company with everything a planning row needs to exist: origin, destinations, a vehicle and a draft run. */
    private record Fixture(UUID companyId, UUID originId, UUID destinationId, UUID secondDestinationId,
            UUID vehicleId, UUID runId) {
    }

    private Fixture fixture(String prefix) throws SQLException {
        UUID organization = insertReturningId("INSERT INTO tms.organization (code, name) VALUES ('ORG-" + prefix
                + "', 'Organization') RETURNING id");
        UUID company = insertReturningId("INSERT INTO tms.company (organization_id, code, name) VALUES ('"
                + organization + "', 'CO-" + prefix + "', 'Company') RETURNING id");
        UUID origin = insertReturningId("INSERT INTO tms.origin (company_id, code, name) VALUES ('" + company
                + "', 'ORIGIN-" + prefix + "', 'Origin') RETURNING id");
        UUID destination = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + company + "', 'DEST1-" + prefix + "', 'Destination one', 'PE') RETURNING id");
        UUID secondDestination = insertReturningId(
                "INSERT INTO tms.destination (company_id, code, name, country) VALUES ('" + company + "', 'DEST2-"
                        + prefix + "', 'Destination two', 'PE') RETURNING id");
        UUID vehicleType = insertReturningId("INSERT INTO tms.vehicle_type"
                + " (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets) VALUES ('" + company
                + "', 'TYPE-" + prefix + "', 'Type', 10000, 40, 20) RETURNING id");
        UUID vehicle = insertReturningId("INSERT INTO tms.vehicle"
                + " (company_id, code, license_plate, vehicle_type_id) VALUES ('" + company + "', 'VEH-" + prefix
                + "', '" + String.format(java.util.Locale.ROOT, "PLT-%04X", prefix.hashCode() & 0xFFFF) + "', '"
                + vehicleType + "') RETURNING id");
        Fixture partial = new Fixture(company, origin, destination, secondDestination, vehicle, null);
        UUID run = insertRunOn(partial, "PL-" + prefix + "-1", "DRAFT", origin, "2026-04-01");
        return new Fixture(company, origin, destination, secondDestination, vehicle, run);
    }

    private UUID insertRun(Fixture fixture, String planNumber, String status) throws SQLException {
        return insertRunOn(fixture, planNumber, status, fixture.originId, "2026-04-01");
    }

    private UUID insertRunOn(Fixture fixture, String planNumber, String status, UUID originId, String planningDate)
            throws SQLException {
        String confirmedAt = "CONFIRMED".equals(status) ? "now()" : "NULL";
        String cancelledAt = "CANCELLED".equals(status) ? "now()" : "NULL";
        return insertReturningId("INSERT INTO tms.planning_run"
                + " (company_id, plan_number, origin_id, planning_date, status, confirmed_at, cancelled_at) VALUES ('"
                + fixture.companyId + "', '" + planNumber + "', '" + originId + "', '" + planningDate + "', '" + status
                + "', " + confirmedAt + ", " + cancelledAt + ") RETURNING id");
    }

    private UUID insertTrip(Fixture fixture, int tripNumber) throws SQLException {
        return insertReturningId("INSERT INTO tms.trip (company_id, planning_run_id, trip_number) VALUES ('"
                + fixture.companyId + "', '" + fixture.runId + "', " + tripNumber + ") RETURNING id");
    }

    private UUID insertOrder(Fixture fixture, String orderNumber) throws SQLException {
        return insertReturningId("INSERT INTO tms.transport_order"
                + " (company_id, order_number, origin_id, destination_id, service_date, status)"
                + " VALUES ('" + fixture.companyId + "', '" + orderNumber + "', '" + fixture.originId + "', '"
                + fixture.destinationId + "', '2026-04-01', 'READY_FOR_PLANNING') RETURNING id");
    }

    private UUID insertStop(Fixture fixture, UUID tripId, UUID destinationId, int sequence) throws SQLException {
        return insertReturningId("INSERT INTO tms.trip_stop (trip_id, company_id, destination_id, sequence)"
                + " VALUES ('" + tripId + "', '" + fixture.companyId + "', '" + destinationId + "', " + sequence
                + ") RETURNING id");
    }

    private UUID insertAssignment(Fixture fixture, UUID tripId, UUID orderId) throws SQLException {
        return insertAssignment(fixture, tripId, orderId, true, 100);
    }

    private UUID insertAssignment(Fixture fixture, UUID tripId, UUID orderId, boolean wholeOrder, int weight)
            throws SQLException {
        return insertReturningId(assignmentInsert(fixture, tripId, orderId, wholeOrder, weight) + " RETURNING id");
    }

    private static String assignmentInsert(Fixture fixture, UUID tripId, UUID orderId, boolean wholeOrder, int weight) {
        return "INSERT INTO tms.trip_order_assignment (company_id, trip_id, order_id, whole_order,"
                + " assigned_weight_kg, assigned_volume_m3, assigned_pallets) VALUES ('" + fixture.companyId + "', '"
                + tripId + "', '" + orderId + "', " + wholeOrder + ", " + weight + ", 1, 1)";
    }

    private UUID insertReturningId(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /** See {@code TenancyConstraintIntegrationTest} for why a savepoint is used here. */
    private void assertViolates(String sqlState, ThrowingCallable statement) {
        try {
            Savepoint savepoint = connection.setSavepoint();
            try {
                Throwable thrown = catchThrowable(statement);
                assertThat(thrown).as("the database was expected to refuse the statement").isNotNull();
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState()).isEqualTo(sqlState);
            } finally {
                connection.rollback(savepoint);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("savepoint handling failed", e);
        }
    }
}
