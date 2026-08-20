package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the V8 master route constraints hold at the database level, independent of the Java
 * validation in {@code RouteService} - the same defense-in-depth proof
 * {@link MasterDataDestinationFrequencyConstraintIntegrationTest} gives V7's tables, extended to
 * routes and route stops.
 *
 * <p>Each test runs in a transaction that is rolled back, matching the sibling constraint
 * tests - except the two tests about {@code uq_route_stop_route_sequence}, which must actually
 * commit to observe a {@code DEFERRABLE INITIALLY DEFERRED} constraint (see the V8 migration
 * comment on that constraint); those two call {@link Connection#commit()} themselves and the
 * {@code @AfterEach} rollback becomes a harmless no-op afterwards.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class MasterDataRouteConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_masterdata_route_constraints");
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

    @Test
    @DisplayName("route codes are unique per company, and free to repeat across companies")
    void codesAreScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID companyA = insertCompany(organization, "MDR-A");
        UUID companyB = insertCompany(organization, "MDR-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID originB = insertOrigin(companyB, "ORIGIN-B");

        insertRoute(companyA, "NORTH-CORRIDOR", originA);
        insertRoute(companyB, "NORTH-CORRIDOR", originB); // same code, different company: allowed
        assertViolates(UNIQUE_VIOLATION, () -> insertRoute(companyA, "NORTH-CORRIDOR", originA));
    }

    @Test
    @DisplayName("a route cannot belong to a company that does not exist")
    void routeRequiresARealCompany() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");

        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> execute("INSERT INTO tms.route (company_id, code, name, origin_id)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'ORPHAN', 'Orphan', '" + origin + "')"));
    }

    @Test
    @DisplayName("codes must already be normalized: upper case, trimmed, no stray characters")
    void codesMustBeNormalized() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");

        assertViolates(CHECK_VIOLATION, () -> insertRoute(company, "lower-case", origin));
        assertViolates(CHECK_VIOLATION, () -> insertRoute(company, "HAS SPACE", origin));
    }

    @Test
    @DisplayName("a route's origin must belong to the same company, even though the FK columns are separate")
    void originMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID companyA = insertCompany(organization, "MDR-A");
        UUID companyB = insertCompany(organization, "MDR-B");
        UUID originInB = insertOrigin(companyB, "ORIGIN-B");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.route"
                + " (company_id, code, name, origin_id) VALUES ('" + companyA
                + "', 'CROSS-ORIGIN', 'Cross origin', '" + originInB + "')"));
    }

    @Test
    @DisplayName("a route's optional zone and frequency must belong to the same company")
    void zoneAndFrequencyMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID companyA = insertCompany(organization, "MDR-A");
        UUID companyB = insertCompany(organization, "MDR-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID zoneInB = insertZone(companyB, "ZONE-B");
        UUID frequencyInB = insertFrequency(companyB, "FREQ-B");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.route"
                + " (company_id, code, name, origin_id, zone_id) VALUES ('" + companyA
                + "', 'CROSS-ZONE', 'Cross zone', '" + originA + "', '" + zoneInB + "')"));
        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.route"
                + " (company_id, code, name, origin_id, frequency_id) VALUES ('" + companyA
                + "', 'CROSS-FREQ', 'Cross frequency', '" + originA + "', '" + frequencyInB + "')"));
    }

    @Test
    @DisplayName("reference distance and duration cannot be negative")
    void referenceValuesMustBeNonnegative() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.route"
                + " (company_id, code, name, origin_id, reference_distance_km) VALUES ('" + company
                + "', 'BAD-DISTANCE', 'Bad distance', '" + origin + "', -1)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.route"
                + " (company_id, code, name, origin_id, reference_duration_minutes) VALUES ('" + company
                + "', 'BAD-DURATION', 'Bad duration', '" + origin + "', -1)"));
    }

    @Test
    @DisplayName("a route stop's destination must belong to the same company as the route")
    void stopDestinationMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID companyA = insertCompany(organization, "MDR-A");
        UUID companyB = insertCompany(organization, "MDR-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID routeA = insertRoute(companyA, "ROUTE-A", originA);
        UUID destinationInB = insertDestination(companyB, "DEST-B");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.route_stop"
                + " (route_id, company_id, destination_id, sequence) VALUES ('" + routeA + "', '" + companyA
                + "', '" + destinationInB + "', 1)"));
    }

    @Test
    @DisplayName("a route stop's company must match its own route's company")
    void stopCompanyMustMatchItsRoute() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID companyA = insertCompany(organization, "MDR-A");
        UUID companyB = insertCompany(organization, "MDR-B");
        UUID originA = insertOrigin(companyA, "ORIGIN-A");
        UUID routeA = insertRoute(companyA, "ROUTE-A", originA);
        UUID destinationInA = insertDestination(companyA, "DEST-A");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.route_stop"
                + " (route_id, company_id, destination_id, sequence) VALUES ('" + routeA + "', '" + companyB
                + "', '" + destinationInA + "', 1)"));
    }

    @Test
    @DisplayName("a stop's sequence must be positive")
    void stopSequenceMustBePositive() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID route = insertRoute(company, "ROUTE-A", origin);
        UUID destination = insertDestination(company, "DEST-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.route_stop"
                + " (route_id, company_id, destination_id, sequence) VALUES ('" + route + "', '" + company
                + "', '" + destination + "', 0)"));
    }

    @Test
    @DisplayName("a destination cannot appear twice on the same route")
    void destinationCannotAppearTwiceOnTheSameRoute() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID route = insertRoute(company, "ROUTE-A", origin);
        UUID destination = insertDestination(company, "DEST-A");

        insertRouteStop(route, company, destination, 1);
        assertViolates(UNIQUE_VIOLATION, () -> insertRouteStop(route, company, destination, 2));
    }

    @Test
    @DisplayName("route stops are deleted when their route is deleted (cascade, no orphans)")
    void stopsCascadeFromRoute() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID route = insertRoute(company, "CASCADED", origin);
        UUID destination = insertDestination(company, "DEST-A");
        insertRouteStop(route, company, destination, 1);

        execute("DELETE FROM tms.route WHERE id = '" + route + "'");

        assertThat(count("SELECT count(*) FROM tms.route_stop WHERE route_id = '" + route + "'")).isZero();
    }

    @Test
    @DisplayName("routes default to active and record who changed them")
    void defaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("MDR-ORG");
        UUID company = insertCompany(organization, "MDR-A");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID actor = insertAppUser("mdr.actor@example.test");

        UUID routeId = insertRoute(company, "DEFAULTS", origin);
        assertThat(count("SELECT count(*) FROM tms.route WHERE id = '" + routeId + "' AND active")).isOne();

        execute("UPDATE tms.route SET active = false, updated_by = '" + actor + "' WHERE id = '" + routeId + "'");
        assertThat(count("SELECT count(*) FROM tms.route WHERE id = '" + routeId
                + "' AND NOT active AND updated_by = '" + actor + "'")).isOne();

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("UPDATE tms.route SET updated_by = '"
                + UUID.randomUUID() + "' WHERE id = '" + routeId + "'"));
    }

    @Test
    @DisplayName("swapping two stops' sequence in place survives commit (deferred unique constraint)")
    void reorderingStopsInPlaceSurvivesCommit() throws SQLException {
        // Unlike every other test in this class, this one actually commits (that is the only way
        // to observe a DEFERRABLE INITIALLY DEFERRED constraint), so its fixture rows are not
        // rolled back by @AfterEach and would collide with "MDR-ORG"/"MDR-A" used everywhere
        // else in this class if JUnit happened to run this test first - hence the dedicated code.
        UUID organization = insertOrganization("MDR-ORG-REORDER");
        UUID company = insertCompany(organization, "MDR-REORDER");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID route = insertRoute(company, "REORDER", origin);
        UUID destinationA = insertDestination(company, "DEST-A");
        UUID destinationB = insertDestination(company, "DEST-B");
        UUID stopA = insertRouteStop(route, company, destinationA, 1);
        UUID stopB = insertRouteStop(route, company, destinationB, 2);

        // Swap: this passes through a transient duplicate (route_id, sequence) pair that a
        // non-deferred constraint would refuse immediately - see the V8 migration comment on
        // uq_route_stop_route_sequence.
        execute("UPDATE tms.route_stop SET sequence = 2 WHERE id = '" + stopA + "'");
        execute("UPDATE tms.route_stop SET sequence = 1 WHERE id = '" + stopB + "'");

        connection.commit();

        assertThat(count("SELECT count(*) FROM tms.route_stop WHERE id = '" + stopA + "' AND sequence = 2")).isOne();
        assertThat(count("SELECT count(*) FROM tms.route_stop WHERE id = '" + stopB + "' AND sequence = 1")).isOne();
    }

    @Test
    @DisplayName("a genuine duplicate sequence still fails, just at commit instead of at the statement")
    void genuineDuplicateSequenceFailsAtCommit() throws SQLException {
        // A failed COMMIT rolls back the whole transaction atomically, so this test's fixture
        // does not actually leak into later tests - but it calls connection.commit() like
        // reorderingStopsInPlaceSurvivesCommit, so it uses the same dedicated-code convention
        // for clarity rather than relying on that atomicity being obvious to the next reader.
        UUID organization = insertOrganization("MDR-ORG-DUPSEQ");
        UUID company = insertCompany(organization, "MDR-DUPSEQ");
        UUID origin = insertOrigin(company, "ORIGIN-A");
        UUID route = insertRoute(company, "DUPLICATE-SEQ", origin);
        UUID destinationA = insertDestination(company, "DEST-A");
        UUID destinationB = insertDestination(company, "DEST-B");

        insertRouteStop(route, company, destinationA, 1);
        insertRouteStop(route, company, destinationB, 1); // does not throw yet: the check is deferred to commit

        Throwable thrown = catchThrowable(() -> connection.commit());
        assertThat(thrown).as("commit was expected to fail: two stops never resolved to distinct sequences").isNotNull();
        assertThat(thrown).isInstanceOf(SQLException.class);
        assertThat(((SQLException) thrown).getSQLState()).isEqualTo(UNIQUE_VIOLATION);
    }

    // --- helpers -----------------------------------------------------------------

    private UUID insertOrganization(String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.organization (code, name) VALUES ('" + code + "', '" + code
                + " name') RETURNING id");
    }

    private UUID insertCompany(UUID organizationId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.company (organization_id, code, name) VALUES ('" + organizationId
                + "', '" + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertAppUser(String email) throws SQLException {
        return insertReturningId(
                "INSERT INTO tms.app_user (email, full_name) VALUES ('" + email + "', 'Test person') RETURNING id");
    }

    /**
     * A canonical location to be used as an origin. No {@code tms.location_role} row: this suite
     * proves database constraints, and which roles a location holds is an application rule the
     * services enforce - {@code RouteApiIntegrationTest} and {@code OrderApiIntegrationTest} are
     * where that is asserted.
     */
    private UUID insertOrigin(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertZone(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.zone (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    /** A canonical location to be used as a destination; see {@link #insertOrigin}. */
    private UUID insertDestination(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.location (company_id, code, name, country) VALUES ('"
                + companyId + "', '" + code + "', '" + code + " name', 'PE') RETURNING id");
    }

    private UUID insertFrequency(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.frequency (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertRoute(UUID companyId, String code, UUID originId) throws SQLException {
        return insertReturningId("INSERT INTO tms.route (company_id, code, name, origin_id) VALUES ('" + companyId
                + "', '" + code + "', '" + code + " name', '" + originId + "') RETURNING id");
    }

    private UUID insertRouteStop(UUID routeId, UUID companyId, UUID destinationId, int sequence) throws SQLException {
        return insertReturningId("INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence)"
                + " VALUES ('" + routeId + "', '" + companyId + "', '" + destinationId + "', " + sequence
                + ") RETURNING id");
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
